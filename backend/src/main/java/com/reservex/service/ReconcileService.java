package com.reservex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.IdCardRoute;
import com.reservex.entity.AuditLog;
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
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import com.reservex.mapper.single.SlotBucketMapper;
import com.reservex.mapper.single.SlotMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.VerificationLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final AuditLogMapper auditLogMapper;
    private final StuckReservationMapper stuckMapper;
    private final StateLogMapper stateLogMapper;
    private final VerificationLogMapper verificationMapper;
    private final StringRedisTemplate redis;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final RollbackService rollbackService;
    private final ReserveXProperties props;
    private final TransactionTemplate singleTx;

    @Autowired
    public ReconcileService(SlotMapper slotMapper,
                            SlotBucketMapper bucketMapper,
                            ReservationMapper reservationMapper,
                            IdCardRouteMapper idCardRouteMapper,
                            ReconcileLogMapper reconcileMapper,
                            AuditLogMapper auditLogMapper,
                            StuckReservationMapper stuckMapper,
                            StateLogMapper stateLogMapper,
                            VerificationLogMapper verificationMapper,
                            StringRedisTemplate redis,
                            IdGenerator idGenerator,
                            TimeSupport time,
                            RollbackService rollbackService,
                            ReserveXProperties props,
                            @Qualifier("singleTxManager") PlatformTransactionManager singleTxManager) {
        this.slotMapper = slotMapper;
        this.bucketMapper = bucketMapper;
        this.reservationMapper = reservationMapper;
        this.idCardRouteMapper = idCardRouteMapper;
        this.reconcileMapper = reconcileMapper;
        this.auditLogMapper = auditLogMapper;
        this.stuckMapper = stuckMapper;
        this.stateLogMapper = stateLogMapper;
        this.verificationMapper = verificationMapper;
        this.redis = redis;
        this.idGenerator = idGenerator;
        this.time = time;
        this.rollbackService = rollbackService;
        this.props = props;
        this.singleTx = singleTxManager == null ? null : new TransactionTemplate(singleTxManager);
    }

    /** Test-only compatibility constructor; production uses the qualified single-db transaction manager. */
    public ReconcileService(SlotMapper slotMapper,
                            SlotBucketMapper bucketMapper,
                            ReservationMapper reservationMapper,
                            IdCardRouteMapper idCardRouteMapper,
                            ReconcileLogMapper reconcileMapper,
                            StuckReservationMapper stuckMapper,
                            StateLogMapper stateLogMapper,
                            VerificationLogMapper verificationMapper,
                            StringRedisTemplate redis,
                            IdGenerator idGenerator,
                            TimeSupport time,
                            RollbackService rollbackService,
                            ReserveXProperties props) {
        this(slotMapper, bucketMapper, reservationMapper, idCardRouteMapper, reconcileMapper,
                null, stuckMapper, stateLogMapper, verificationMapper, redis,
                idGenerator, time, rollbackService, props, null);
    }

    /** Test-only compatibility constructor; production uses the qualified single-db transaction manager. */
    public ReconcileService(SlotMapper slotMapper,
                            SlotBucketMapper bucketMapper,
                            ReservationMapper reservationMapper,
                            IdCardRouteMapper idCardRouteMapper,
                            ReconcileLogMapper reconcileMapper,
                            AuditLogMapper auditLogMapper,
                            StuckReservationMapper stuckMapper,
                            StateLogMapper stateLogMapper,
                            VerificationLogMapper verificationMapper,
                            StringRedisTemplate redis,
                            IdGenerator idGenerator,
                            TimeSupport time,
                            RollbackService rollbackService,
                            ReserveXProperties props) {
        this(slotMapper, bucketMapper, reservationMapper, idCardRouteMapper, reconcileMapper,
                auditLogMapper, stuckMapper, stateLogMapper, verificationMapper, redis,
                idGenerator, time, rollbackService, props, null);
    }

    @Scheduled(cron = "${reservex.reconcile.crons.stock:0 */5 * * * ?}")
    public void reconcileStock() {
        LocalDate today = time.today();
        reconcileDate(today);
        reconcileDate(today.plusDays(1));
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
     * 且身份证 hash、日期一致。取消/过期仍消耗当日配额，不是幽灵 route。
     * 记 diff **不自动删**。{@link com.reservex.worker.OrphanRouteCleaner} 同样只检测注册跨库两写
     * 留下的疑似孤儿;本任务对的是配额位与预约记录的对应关系。
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
        long last = Math.max(0, props.getReconcile().getPageSize() - 1L);
        Set<String> pending = redis.opsForZSet().range(ReservationService.PENDING_KEY, 0, last);
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
        LocalDateTime now = time.now();
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
            boolean bucketMismatch = !matchesDatabaseBuckets(slot, buckets, values);
            int active = Math.toIntExact(reservationMapper.selectCount(
                    new LambdaQueryWrapper<Reservation>()
                            .eq(Reservation::getSlotId, slot.getSlotId())
                            .in(Reservation::getStatus, 0, 1)));

            ReconcileLog log = new ReconcileLog();
            log.setId(idGenerator.nextId());
            log.setTaskType(taskType);
            log.setPeriod(now.format(PERIOD));
            log.setSlotId(slot.getSlotId());
            log.setRedisOccupied(redisOccupied);
            log.setDbOccupied(dbOccupied);
            log.setReservationCnt(active);
            log.setDiff(redisOccupied - dbOccupied);
            if (bucketMismatch) {
                log.setFixAction("bucket-mismatch");
            }
            log.setCreateAt(now);
            reconcileMapper.insertIgnore(log);
        }
    }

    /** reconcile-b:反向记 diff = dbOccupied - redisOccupied。见 {@link #reconcileB}。 */
    private void reconcileDateReverse(LocalDate date, String taskType) {
        LocalDateTime now = time.now();
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
            boolean bucketMismatch = !matchesDatabaseBuckets(slot, buckets, values);
            int active = Math.toIntExact(reservationMapper.selectCount(
                    new LambdaQueryWrapper<Reservation>()
                            .eq(Reservation::getSlotId, slot.getSlotId())
                            .in(Reservation::getStatus, 0, 1)));

            ReconcileLog log = new ReconcileLog();
            log.setId(idGenerator.nextId());
            log.setTaskType(taskType);
            log.setPeriod(now.format(PERIOD));
            log.setSlotId(slot.getSlotId());
            log.setRedisOccupied(redisOccupied);
            log.setDbOccupied(dbOccupied);
            log.setReservationCnt(active);
            log.setDiff(dbOccupied - redisOccupied);
            if (bucketMismatch) {
                log.setFixAction("bucket-mismatch");
            }
            log.setCreateAt(now);
            reconcileMapper.insertIgnore(log);
        }
    }

    /**
     * route 对账核心:扫指定日 id_card_route,查对应 reservation 是否存在且字段一致。
     *
     * <p>⚠️ id_card_route 的复合主键含 {@code slot_date},按 slot_date=? 查命中本表
     * (单库表,不分片)。reservation 查按 reservation_no(主键,广播两库各一次主键查询,快)。
     */
    private int scanRouteGhosts(LocalDate date) {
        List<IdCardRoute> routes = idCardRouteMapper.selectBySlotDate(date);
        int ghosts = 0;
        for (IdCardRoute route : routes) {
            Reservation r = reservationMapper.selectById(route.getReservationNo());
            if (r == null || !Objects.equals(route.getIdCardHash(), r.getIdCardHash())
                    || !Objects.equals(route.getSlotDate(), r.getSlotDate())) {
                ghosts++;
            }
        }
        // 反向检查 reservation -> route，覆盖“预约已落库但阶段二在 route 写入前崩溃”。
        // 这是低频对账路径，广播查询和逐行主键查可接受；不能只做 route -> reservation。
        List<Reservation> reservations = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSlotDate, date));
        for (Reservation reservation : reservations) {
            Long owner = idCardRouteMapper.selectReservationNo(
                    reservation.getIdCardHash(), reservation.getSlotDate());
            if (!Objects.equals(owner, reservation.getReservationNo())) {
                ghosts++;
            }
        }
        return ghosts;
    }

    private static boolean matchesDatabaseBuckets(Slot slot, List<SlotBucket> buckets,
                                                   List<String> values) {
        if (buckets.size() != slot.getBucketCount() || values == null
                || values.size() != slot.getBucketCount()) {
            return false;
        }
        for (int bucketNo = 0; bucketNo < slot.getBucketCount(); bucketNo++) {
            final int expectedBucketNo = bucketNo;
            SlotBucket bucket = buckets.stream()
                    .filter(candidate -> Integer.valueOf(expectedBucketNo).equals(candidate.getBucketNo()))
                    .findFirst().orElse(null);
            String value = values.get(bucketNo);
            if (bucket == null || value == null) {
                return false;
            }
            try {
                if (Integer.parseInt(value) != bucket.getTotal() - bucket.getOccupied()) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
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
        long diff = reconcileMapper.countCurrentWithDiff();
        long stuck = stuckMapper.selectCount(new LambdaQueryWrapper<StuckReservation>()
                .in(StuckReservation::getStatus, 0, 4));
        return new Dashboard(slots.size(), reservations, verified, diff, stuck);
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

    public record VerifyStatsView(long confirmed, long verified, long cancelled, long expired,
                                  long successToday, long attemptsToday) {
    }

    /**
     * 对账中心人工处置。
     *
     * <p><b>stuck</b>:rollback 同步调用 RollbackService 的 compensate.lua 幂等回滚,
     * 确认真实回补后才 resolve status=2。不能用“忽略”跳过业务收口。
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
                    "不支持的对账处置类型,请使用 /api/admin/stuck-reservations 或 /api/admin/dead-letter-messages");
        };
    }

    public StuckReservation resolveStuck(long reservationNo, String targetStatus, long resolverId) {
        String action = switch (targetStatus) {
            case "ROLLED_BACK" -> "rollback";
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的卡单目标状态");
        };
        handleStuckAction(reservationNo, action, resolverId);
        StuckReservation resolved = stuckMapper.selectById(reservationNo);
        if (resolved == null) {
            throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
        }
        return resolved;
    }

    private int handleStuckAction(long rno, String action, long resolverId) {
        return switch (action) {
            case "rollback" -> rollbackStuck(rno, resolverId);
            default -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "未知 stuck 处置动作:" + action + ",仅支持 rollback");
        };
    }

    private int rollbackStuck(long rno, long resolverId) {
        StuckReservation stuck = stuckMapper.selectById(rno);
        if (stuck == null) {
            throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
        }
        if (stuck.getBucketKey() == null || stuck.getDupKey() == null) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "卡单缺少回滚参数(bucket/dup key),occupy 可能已过期,无法自动回滚");
        }
        int claimed = stuckMapper.transition(rno, 0, 4, resolverId, time.now());
        if (claimed == 0) {
            stuck = stuckMapper.selectById(rno);
            if (stuck == null || stuck.getStatus() != 4) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "卡单已被其他人员处理");
            }
        }

        String xid = "rx-" + rno;
        String occupyKey = ReservationService.occupyKey(rno);
        boolean rollbackPending = "1".equals(String.valueOf(
                redis.opsForHash().get(occupyKey, "rollback_pending")));
        boolean alreadyCompensated = Boolean.TRUE.equals(
                redis.hasKey(RollbackService.doneKey(rno)));
        Reservation reservation = reservationMapper.selectById(rno);
        if (reservation != null && !rollbackPending && !alreadyCompensated) {
            releaseRollbackClaim(rno, resolverId, claimed);
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "预约已完成持久化，缺少明确回滚标记，拒绝回补库存");
        }
        if (reservation != null && reservation.getStatus() != 2
                && reservation.getStatus() != 3) {
            releaseRollbackClaim(rno, resolverId, claimed);
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "预约已核销或正在变更，拒绝回补库存");
        }
        CompensateRollbackMessage msg = new CompensateRollbackMessage(
                "manual-rollback-" + rno, rno, stuck.getDupKey(),
                stuck.getBucketKey(), "slot:full:" + stuck.getSlotId(), "MANUAL_ROLLBACK",
                "admin-action-" + rno);
        stateLogMapper.insertRollbackClaim(xid, Long.toString(rno));
        var rollbackState = stateLogMapper.selectById(xid);
        if (rollbackState == null) {
            releaseRollbackClaim(rno, resolverId, claimed);
            throw new IllegalStateException("rollback state missing after claim xid=" + xid);
        }
        if (rollbackState.getStatus() == 3) {
            if (Boolean.TRUE.equals(redis.hasKey(RollbackService.doneKey(rno)))) {
                return finishRollback(rno, resolverId);
            }
            if (stateLogMapper.promoteRollbackClaim(xid) != 1) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "回滚仲裁状态已变化，请重试");
            }
            rollbackState = stateLogMapper.selectById(xid);
        }
        if (rollbackState.getStatus() != 4) {
            releaseRollbackClaim(rno, resolverId, claimed);
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "预约落库事务已开始，拒绝并发回补库存");
        }

        // claim 后阻止 persistence-consumer 在 consumed_event 分支清理 occupy。
        redis.opsForHash().put(occupyKey, "rollback_pending", "1");
        if (!rollbackService.compensate(msg)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "卡单回滚未执行:没有可证明的 occupy 或 done marker");
        }
        var completed = stateLogMapper.selectById(xid);
        if (completed == null || completed.getStatus() != 3) {
            throw new BizException(ErrorCode.SERVICE_DEGRADED, "库存已处理但回滚日志未落下，请重试");
        }
        int n = finishRollback(rno, resolverId);
        log.warn("人工回滚卡单 rno={} resolved={}", rno, n);
        return n;
    }

    private int finishRollback(long rno, long resolverId) {
        java.util.function.Supplier<Integer> finish = () -> {
            int n = stuckMapper.transition(rno, 4, 2, resolverId, time.now());
            if (n == 0) {
                StuckReservation latest = stuckMapper.selectById(rno);
                if (latest != null && latest.getStatus() == 2) {
                    return 0;
                }
                throw new BizException(ErrorCode.STATE_CONFLICT, "卡单已被其他人员处理");
            }
            recordRollbackAudit(rno, resolverId);
            return n;
        };
        return singleTx == null ? finish.get() : singleTx.execute(status -> finish.get());
    }

    private void releaseRollbackClaim(long rno, long resolverId, int claimed) {
        if (claimed != 1) {
            return;
        }
        stuckMapper.transition(rno, 4, 0, resolverId, time.now());
    }

    private void recordRollbackAudit(long rno, long resolverId) {
        if (auditLogMapper == null) {
            return;
        }
        AuditLog audit = new AuditLog();
        audit.setId(idGenerator.nextId());
        audit.setOperatorType("ADMIN");
        audit.setOperatorId(resolverId);
        audit.setAction("STUCK_ROLLBACK");
        audit.setTargetType("RESERVATION");
        audit.setTargetId(rno);
        audit.setBefore("{\"status\":\"STUCK\"}");
        audit.setAfter("{\"status\":\"ROLLED_BACK\"}");
        audit.setRequestId("stuck-rollback-" + rno);
        audit.setCreateAt(time.now());
        if (auditLogMapper.insert(audit) != 1) {
            throw new BizException(ErrorCode.SERVICE_DEGRADED, "卡单回滚审计写入失败，请重试");
        }
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
            log.info("diff fix 告警 slotId={} diff={} 待人工按桶补齐 Redis 余量", logEntry.getSlotId(), logEntry.getDiff());
        } else {
            log.warn("diff 为负(Redis 余量 > DB 该有值)疑似超卖 slotId={} diff={},不自动改", logEntry.getSlotId(), logEntry.getDiff());
        }
        // 没有逐桶真值就不能把“记录了意向”报告成“已修复”。
        throw new BizException(ErrorCode.BAD_REQUEST, "库存差异未自动修复，请按桶人工核对");
    }

    public record Dashboard(int todaySlots, long todayReservations, long todayVerified,
                            long reconcileDiffCount, long stuckCount) {
    }
}
