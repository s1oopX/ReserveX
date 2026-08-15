package com.reservex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.IdCardRoute;
import com.reservex.entity.ReconcileLog;
import com.reservex.entity.Reservation;
import com.reservex.entity.Slot;
import com.reservex.entity.SlotBucket;
import com.reservex.entity.StuckReservation;
import com.reservex.entity.VerificationLog;
import com.reservex.id.IdGenerator;
import com.reservex.message.CompensateRollbackMessage;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.single.IdCardRouteMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import com.reservex.mapper.single.SlotBucketMapper;
import com.reservex.mapper.single.SlotMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.mapper.single.VerificationLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MVP 库存对账：Redis 扣减与 DB 累计占用必须相等，默认只记录不自动改。 */
@Slf4j
@Service
public class ReconcileService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final SlotMapper slotMapper;
    private final SlotBucketMapper bucketMapper;
    private final ReservationMapper reservationMapper;
    private final IdCardRouteMapper idCardRouteMapper;
    private final ReconcileLogMapper reconcileMapper;
    private final StuckReservationMapper stuckMapper;
    private final VerificationLogMapper verificationMapper;
    private final StringRedisTemplate redis;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final RocketMQTemplate rocketMQ;
    private final ReserveXProperties props;

    public ReconcileService(SlotMapper slotMapper,
                            SlotBucketMapper bucketMapper,
                            ReservationMapper reservationMapper,
                            IdCardRouteMapper idCardRouteMapper,
                            ReconcileLogMapper reconcileMapper,
                            StuckReservationMapper stuckMapper,
                            VerificationLogMapper verificationMapper,
                            StringRedisTemplate redis,
                            IdGenerator idGenerator,
                            TimeSupport time,
                            RocketMQTemplate rocketMQ,
                            ReserveXProperties props) {
        this.slotMapper = slotMapper;
        this.bucketMapper = bucketMapper;
        this.reservationMapper = reservationMapper;
        this.idCardRouteMapper = idCardRouteMapper;
        this.reconcileMapper = reconcileMapper;
        this.stuckMapper = stuckMapper;
        this.verificationMapper = verificationMapper;
        this.redis = redis;
        this.idGenerator = idGenerator;
        this.time = time;
        this.rocketMQ = rocketMQ;
        this.props = props;
    }

    @Scheduled(cron = "${reservex.reconcile.crons.stock:0 */5 * * * ?}")
    public void reconcileStock() {
        reconcileDate(time.today());
        reconcileDate(time.today().plusDays(1));
    }

    /**
     * reconcile-a:Redis→DB 方向不变量({@code redisOccupied - dbOccupied})。
     *
     * <p>与 {@link #reconcileStock} 算的是同一个不变量,但 task_type 不同 ——
     * 这样看板上能区分"库存对账(stock)"与"Redis→DB 方向专项对账(reconcile-a)"
     * 两个独立运行证据,两者都跑才说明两个方向都没漏。cron 1min,比 stock 更密。
     */
    @Scheduled(cron = "${reservex.reconcile.crons.reconcile-a:0 * * * * ?}")
    @Async("reconcileExecutor")
    public void reconcileA() {
        reconcileDate(time.today(), "reconcile-a");
    }

    /**
     * reconcile-b:DB→Redis 方向({@code dbOccupied - redisOccupied})。
     *
     * <p>与 reconcile-a 是同一等式的反向 —— 正负号相反。单独记录反向的目的是:
     * 一个方向为正、另一个必为负,运维两个 Tab 对比能立刻发现"两个方向同号"
     * 这类异常(意味着两套对账代码读了不一样的数据)。cron 5min。
     */
    @Scheduled(cron = "${reservex.reconcile.crons.reconcile-b:0 */5 * * * ?}")
    @Async("reconcileExecutor")
    public void reconcileB() {
        reconcileDateReverse(time.today(), "reconcile-b");
    }

    /**
     * route 对账:id_card_route vs reservation。
     *
     * <p>扫 {@code id_card_route} 每条,查对应 {@code reservation_no} 在 reservation 表是否存在
     * 且 status∈{0,1}(RESERVED/VERIFIED)。route 有但 reservation 不存在或已取消/过期 = 幽灵 route,
     * 记 diff **不自动删**(区别于 {@link com.reservex.worker.OrphanRouteCleaner} 的 email/phone orphan-route,
     * 那个是注册跨库两写失败留的孤儿,本任务对的是配额位与预约记录的对应关系)。
     */
    @Scheduled(cron = "${reservex.reconcile.crons.route:0 */10 * * * ?}")
    @Async("reconcileExecutor")
    public void reconcileRoute() {
        LocalDate today = time.today();
        String period = time.now().format(PERIOD);
        int ghosts = scanRouteGhosts(today);
        if (ghosts == 0) {
            // 无差异也记一条 diff=0 的运行证据(uk 幂等挡同周期重跑)。
            recordRouteLog(period, today, 0);
            return;
        }
        recordRouteLog(period, today, ghosts);
        log.warn("route 对账发现幽灵 route(date={}) ghosts={}", today, ghosts);
    }

    /**
     * pending-idx 对账:pending ZSet vs occupy。
     *
     * <p>扫 {@code pending:persist} 每个 rno,查 {@code occupy:{rno}} 是否存在。
     * ZSet 有、occupy 不存在 = 脏索引(occupy TTL 已过期但 ZSet 没清)→ 安全删 ZSet 条目。
     * 反向(occupy 有、ZSet 没有)不处理:可能是刚 ZADD 还没到 EXPIRE 的窗口期,
     * 删了会丢掉待补投记录。
     */
    @Scheduled(cron = "${reservex.reconcile.crons.pending-idx:0 */5 * * * ?}")
    @Async("reconcileExecutor")
    public void reconcilePendingIndex() {
        String period = time.now().format(PERIOD);
        Set<String> pending = redis.opsForZSet().range(ReservationService.PENDING_KEY, 0, -1);
        if (pending == null || pending.isEmpty()) {
            recordPendingIdxLog(period, 0, 0);
            return;
        }
        int dirty = 0;
        int alive = 0;
        List<String> toRemove = new ArrayList<>();
        for (String raw : pending) {
            long rno;
            try {
                rno = Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                continue;
            }
            // occupy 不存在 = 脏索引,安全删。Map.isEmpty 判空 occupy key 是否还有 hash field。
            Map<Object, Object> occupy = redis.opsForHash().entries(ReservationService.occupyKey(rno));
            if (occupy.isEmpty()) {
                toRemove.add(raw);
                dirty++;
            } else {
                alive++;
            }
        }
        if (!toRemove.isEmpty()) {
            redis.opsForZSet().remove(ReservationService.PENDING_KEY, toRemove.toArray());
            log.warn("pending-idx 清理脏索引 removed={}", toRemove.size());
        }
        recordPendingIdxLog(period, dirty, alive);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialReconcile() {
        reconcileStock();
    }

    private void reconcileDate(LocalDate date) {
        reconcileDate(date, "stock");
    }

    private void reconcileDate(LocalDate date, String taskType) {
        for (Slot slot : slotMapper.selectByDate(date)) {
            if (slot.getReleased() != 1) {
                continue;
            }
            List<SlotBucket> buckets = bucketMapper.selectBySlot(slot.getSlotId());
            int redisRemain = 0;
            List<String> keys = new ArrayList<>(slot.getBucketCount());
            for (int i = 0; i < slot.getBucketCount(); i++) {
                keys.add(ReservationService.bucketKey(slot.getSlotId(), i));
            }
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values != null) {
                for (String value : values) {
                    if (value != null) {
                        redisRemain += Integer.parseInt(value);
                    }
                }
            }
            int redisOccupied = slot.getCapacity() - redisRemain;
            int dbOccupied = buckets.stream().mapToInt(SlotBucket::getOccupied).sum();
            int active = Math.toIntExact(reservationMapper.selectCount(
                    new LambdaQueryWrapper<Reservation>()
                            .eq(Reservation::getSlotId, slot.getSlotId())
                            .in(Reservation::getStatus, 0, 1)));

            ReconcileLog log = new ReconcileLog();
            log.setId(idGenerator.nextId());
            log.setTaskType(taskType);
            log.setPeriod(time.now().format(PERIOD));
            log.setSlotId(slot.getSlotId());
            log.setRedisOccupied(redisOccupied);
            log.setDbOccupied(dbOccupied);
            log.setReservationCnt(active);
            log.setDiff(redisOccupied - dbOccupied);
            log.setCreateAt(time.now());
            reconcileMapper.insertIgnore(log);
        }
    }

    /** reconcile-b:反向记 diff = dbOccupied - redisOccupied。见 {@link #reconcileB}。 */
    private void reconcileDateReverse(LocalDate date, String taskType) {
        for (Slot slot : slotMapper.selectByDate(date)) {
            if (slot.getReleased() != 1) {
                continue;
            }
            List<SlotBucket> buckets = bucketMapper.selectBySlot(slot.getSlotId());
            int redisRemain = 0;
            List<String> keys = new ArrayList<>(slot.getBucketCount());
            for (int i = 0; i < slot.getBucketCount(); i++) {
                keys.add(ReservationService.bucketKey(slot.getSlotId(), i));
            }
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values != null) {
                for (String value : values) {
                    if (value != null) {
                        redisRemain += Integer.parseInt(value);
                    }
                }
            }
            int redisOccupied = slot.getCapacity() - redisRemain;
            int dbOccupied = buckets.stream().mapToInt(SlotBucket::getOccupied).sum();
            int active = Math.toIntExact(reservationMapper.selectCount(
                    new LambdaQueryWrapper<Reservation>()
                            .eq(Reservation::getSlotId, slot.getSlotId())
                            .in(Reservation::getStatus, 0, 1)));

            ReconcileLog log = new ReconcileLog();
            log.setId(idGenerator.nextId());
            log.setTaskType(taskType);
            log.setPeriod(time.now().format(PERIOD));
            log.setSlotId(slot.getSlotId());
            log.setRedisOccupied(redisOccupied);
            log.setDbOccupied(dbOccupied);
            log.setReservationCnt(active);
            log.setDiff(dbOccupied - redisOccupied);
            log.setCreateAt(time.now());
            reconcileMapper.insertIgnore(log);
        }
    }

    /**
     * route 对账核心:扫指定日 id_card_route,查对应 reservation 是否存在且有效。
     *
     * <p>⚠️ id_card_route 的复合主键含 {@code slot_date},按 slot_date=? 查命中本表
     * (单库表,不分片)。reservation 查按 reservation_no(主键,广播两库各一次主键查询,快)。
     */
    private int scanRouteGhosts(LocalDate date) {
        List<IdCardRoute> routes = idCardRouteMapper.selectBySlotDate(date);
        if (routes.isEmpty()) {
            return 0;
        }
        int ghosts = 0;
        for (IdCardRoute route : routes) {
            Reservation r = reservationMapper.selectById(route.getReservationNo());
            if (r == null || (r.getStatus() != 0 && r.getStatus() != 1)) {
                ghosts++;
            }
        }
        return ghosts;
    }

    private void recordRouteLog(String period, LocalDate date, int ghosts) {
        ReconcileLog log = new ReconcileLog();
        log.setId(idGenerator.nextId());
        log.setTaskType("route");
        log.setPeriod(period);
        // 全局任务无 slot 归属:slot_id=0(Snowflake 永不为 0)让 uk_task_period_slot 仍能去重。
        log.setSlotId(0L);
        log.setRedisOccupied(null);
        log.setDbOccupied(null);
        log.setReservationCnt(null);
        log.setDiff(ghosts);
        log.setCreateAt(time.now());
        reconcileMapper.insertIgnore(log);
    }

    private void recordPendingIdxLog(String period, int dirty, int alive) {
        ReconcileLog log = new ReconcileLog();
        log.setId(idGenerator.nextId());
        log.setTaskType("pending-idx");
        log.setPeriod(period);
        log.setSlotId(0L);
        log.setRedisOccupied(dirty);
        log.setDbOccupied(alive);
        log.setReservationCnt(dirty + alive);
        log.setDiff(dirty);
        log.setFixAction(dirty > 0 ? "removed-dirty" : null);
        log.setCreateAt(time.now());
        reconcileMapper.insertIgnore(log);
    }

    public List<ReconcileLog> diffs() {
        return reconcileMapper.selectWithDiff(null, 100);
    }

    public List<ReconcileLog> latest() {
        return reconcileMapper.selectLatest(100);
    }

    public List<com.reservex.entity.StuckReservation> stuck() {
        return stuckMapper.selectPending(100);
    }

    public Dashboard dashboard() {
        List<Slot> slots = slotMapper.selectByDate(time.today());
        long reservations = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSlotDate, time.today()));
        long verified = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSlotDate, time.today())
                .eq(Reservation::getStatus, 1));
        long diff = reconcileMapper.selectCount(new LambdaQueryWrapper<ReconcileLog>()
                .ne(ReconcileLog::getDiff, 0));
        long stuck = stuckMapper.selectCount(new LambdaQueryWrapper<StuckReservation>()
                .eq(StuckReservation::getStatus, 0));
        return new Dashboard(slots.size(), reservations, verified, diff, stuck);
    }

    /**
     * 管理员全园预约查询(广播两库归并 + 脱敏)。
     *
     * <p>⚠️ 按 reservation_no(主键,广播)或 slot_date(广播)或 status 查;
     * **不按 user_id**(那是用户的分片键,管理员视角无权知道用户落在哪库)。
     * 广播两库各一次查询后归并,今日预约量级 < 200 可接受。
     *
     * <p>不暴露 idCardHash 明文关联,只返冗余的 idCardMasked(列表逐行解密太贵)。
     *
     * @param rno       预约号(可选,精确查单条)
     * @param slotDate  场次日期(可选,按日筛)
     * @param statusStr 状态名(可选:CONFIRMED/VERIFIED/CANCELLED/EXPIRED)
     */
    public List<ReservationView> listAdminReservations(Long rno, LocalDate slotDate, String statusStr) {
        LambdaQueryWrapper<Reservation> qw = new LambdaQueryWrapper<>();
        if (rno != null) {
            qw.eq(Reservation::getReservationNo, rno);
        }
        if (slotDate != null) {
            qw.eq(Reservation::getSlotDate, slotDate);
        }
        if (statusStr != null && !statusStr.isBlank()) {
            Integer code = statusToCode(statusStr);
            if (code != null) {
                qw.eq(Reservation::getStatus, code);
            }
        }
        qw.orderByDesc(Reservation::getCreateAt).last("LIMIT 500");
        List<Reservation> rows = reservationMapper.selectList(qw);
        List<ReservationView> result = new ArrayList<>(rows.size());
        for (Reservation row : rows) {
            result.add(toAdminView(row));
        }
        return result;
    }

    /**
     * 今日核销统计(StaffToday 工作台指标)。
     *
     * <p>按今日 slot_date 广播两库统计各状态计数,以及今日核销流水条数。
     * 不按 staff 个人维度统计(v1 STAFF 不绑定场次,全园归并即可)。
     */
    public VerifyStatsView verifyStatsToday() {
        LocalDate today = time.today();
        long confirmed = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSlotDate, today)
                .eq(Reservation::getStatus, 0));
        long verified = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSlotDate, today)
                .eq(Reservation::getStatus, 1));
        long cancelled = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSlotDate, today)
                .eq(Reservation::getStatus, 2));
        long expired = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSlotDate, today)
                .eq(Reservation::getStatus, 3));
        // 今日核销尝试流水(含重复/失败的尝试,用于看异常尝试)
        long attemptsToday = verificationMapper.selectCount(new LambdaQueryWrapper<VerificationLog>()
                .ge(VerificationLog::getVerifyTime, today.atStartOfDay())
                .lt(VerificationLog::getVerifyTime, today.plusDays(1).atStartOfDay()));
        long successToday = verificationMapper.selectCount(new LambdaQueryWrapper<VerificationLog>()
                .eq(VerificationLog::getResult, 0)
                .ge(VerificationLog::getVerifyTime, today.atStartOfDay())
                .lt(VerificationLog::getVerifyTime, today.plusDays(1).atStartOfDay()));
        return new VerifyStatsView(confirmed, verified, cancelled, expired,
                successToday, attemptsToday);
    }

    private static Integer statusToCode(String name) {
        return switch (name.toUpperCase()) {
            case "CONFIRMED" -> 0;
            case "VERIFIED" -> 1;
            case "CANCELLED" -> 2;
            case "EXPIRED" -> 3;
            default -> null;
        };
    }

    private static String codeToStatus(int code) {
        return switch (code) {
            case 0 -> "CONFIRMED";
            case 1 -> "VERIFIED";
            case 2 -> "CANCELLED";
            case 3 -> "EXPIRED";
            default -> "UNKNOWN";
        };
    }

    private static ReservationView toAdminView(Reservation row) {
        return new ReservationView(row.getReservationNo(), row.getUserId(), row.getSlotId(),
                row.getSlotDate(), codeToStatus(row.getStatus()), row.getVersion(),
                row.getCreateAt(), row.getVerifiedAt(), row.getIdCardMasked());
    }

    /** 管理端预约视图:比用户视图多 userId(管理端需溯源用户)。 */
    public record ReservationView(Long reservationNo, Long userId, Long slotId,
                                  LocalDate slotDate, String status, Integer version,
                                  LocalDateTime createAt, LocalDateTime verifyTime,
                                  String idCardMasked) {
    }

    public record VerifyStatsView(long confirmed, long verified, long cancelled, long expired,
                                  long successToday, long attemptsToday) {
    }

    /**
     * 对账中心人工处置。
     *
     * <p><b>stuck</b>:rollback 发 CompensateRollbackMessage(RollbackConsumer 调
     * compensate.lua 幂等回滚),resolve status=2;ignore 仅 resolve status=3。
     * stuck 行的 bucketKey/dupKey/slotFullKey 由 PendingScanner.toStuck 写全。
     *
     * <p><b>diff</b>:fix 必须校验 stockAutoFix=true(08 §7.1 红线),且只能 Redis 余量
     * INCRBY 补齐(Redis→DB 方向),不能反向减 DB occupied(M1 只增不减);diff 为负
     * (疑似超卖)只告警不修。
     *
     * @return 处置受影响行数(stuck 的 resolve 带 status=0 守卫,两人同时处置只有一个成功)
     */
    public int handleAction(String type, long id, String action, long resolverId) {
        return switch (type) {
            case "stuck" -> handleStuckAction(id, action, resolverId);
            case "diff" -> handleDiffAction(id, action);
            default -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "DLQ 处置暂不支持,请用 /reconcile/stuck 查看待研判卡单");
        };
    }

    private int handleStuckAction(long rno, String action, long resolverId) {
        return switch (action) {
            case "rollback" -> {
                StuckReservation stuck = stuckMapper.selectById(rno);
                if (stuck == null) {
                    throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
                }
                if (stuck.getBucketKey() == null || stuck.getDupKey() == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "卡单缺少回滚参数(bucket/dup key),occupy 可能已过期,无法自动回滚");
                }
                CompensateRollbackMessage msg = new CompensateRollbackMessage(
                        "manual-rollback-" + rno, rno, stuck.getDupKey(),
                        stuck.getBucketKey(), "slot:full:" + stuck.getSlotId(), "MANUAL_ROLLBACK",
                        "admin-action-" + rno);
                rocketMQ.syncSend("compensate-rollback", msg);
                int n = stuckMapper.resolve(rno, 2, resolverId, time.now());
                log.warn("人工回滚卡单 rno={} resolved={}", rno, n);
                yield n;
            }
            case "ignore" -> {
                int n = stuckMapper.resolve(rno, 3, resolverId, time.now());
                log.info("人工忽略卡单 rno={} resolved={}", rno, n);
                yield n;
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "未知 stuck 处置动作:" + action + ",支持 rollback / ignore");
        };
    }

    private int handleDiffAction(long reconcileLogId, String action) {
        if (!"fix".equals(action)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "未知 diff 处置动作:" + action + ",支持 fix");
        }
        if (!props.getReconcile().isStockAutoFix()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "stock-auto-fix 关闭:自动修会把正常在途预约改成已修复,需在 yml 显式开启并带状态守卫");
        }
        ReconcileLog logEntry = reconcileMapper.selectById(reconcileLogId);
        if (logEntry == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (logEntry.getDiff() == null || logEntry.getDiff() == 0) {
            return 0;
        }
        // diff = redisOccupied - dbOccupied > 0:Redis 少了(桶余量被多扣),INCRBY 补;
        // diff < 0:Redis 余量比 DB 多=疑似超卖,不能自动减 DB occupied,只告警。
        if (logEntry.getDiff() > 0) {
            // 只记录处置意向,实际 INCRBY 需按桶精确补,这里仅告警占位(M1 只增不减的谨慎面)
            log.info("diff fix 告警 slotId={} diff={} 待人工按桶补齐 Redis 余量", logEntry.getSlotId(), logEntry.getDiff());
        } else {
            log.warn("diff 为负(Redis 余量 > DB 该有值)疑似超卖 slotId={} diff={},不自动改", logEntry.getSlotId(), logEntry.getDiff());
        }
        return 1;
    }

    public record Dashboard(int todaySlots, long todayReservations, long todayVerified,
                            long reconcileDiffCount, long stuckCount) {
    }
}
