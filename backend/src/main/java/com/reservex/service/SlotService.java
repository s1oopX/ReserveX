package com.reservex.service;

import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.HttpPreconditions;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.AuditLog;
import com.reservex.entity.Slot;
import com.reservex.entity.SlotBucket;
import com.reservex.entity.SlotTemplate;
import com.reservex.id.IdGenerator;
import com.reservex.lua.LuaScripts;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.SlotBucketMapper;
import com.reservex.mapper.single.SlotMapper;
import com.reservex.mapper.single.SlotTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** 场次生成、放号、库存恢复和查询。模板 CRUD 属于 SlotTemplateAdminService。 */
@Slf4j
@Service
public class SlotService {

    private final SlotTemplateMapper templateMapper;
    private final SlotMapper slotMapper;
    private final SlotBucketMapper bucketMapper;
    private final AuditLogMapper auditMapper;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final ReserveXProperties props;
    private final LuaScripts lua;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final TransactionTemplate singleTx;

    public SlotService(SlotTemplateMapper templateMapper,
                       SlotMapper slotMapper,
                       SlotBucketMapper bucketMapper,
                       AuditLogMapper auditMapper,
                       IdGenerator idGenerator,
                       TimeSupport time,
                       ReserveXProperties props,
                       LuaScripts lua,
                       StringRedisTemplate redis,
                       RedissonClient redisson,
                       @Qualifier("singleTxManager") PlatformTransactionManager txManager) {
        this.templateMapper = templateMapper;
        this.slotMapper = slotMapper;
        this.bucketMapper = bucketMapper;
        this.auditMapper = auditMapper;
        this.idGenerator = idGenerator;
        this.time = time;
        this.props = props;
        this.lua = lua;
        this.redis = redis;
        this.redisson = redisson;
        this.singleTx = new TransactionTemplate(txManager);
    }

    @Scheduled(cron = "${reservex.slot.gen-cron}")
    public void generateScheduled() {
        generateDate(time.today().plusDays(props.getSlot().getGenDaysAhead()));
    }

    @Scheduled(cron = "${reservex.reconcile.crons.release:0 * * * * ?}")
    public void releaseDue() {
        repairReleasedCaches();
        for (Slot slot : slotMapper.selectDueForRelease(time.now())) {
            try {
                release(slot);
            } catch (RuntimeException e) {
                // CAS 已成功但 Redis 失败时保留 released=1；下一轮 repairReleasedCaches 收敛。
                log.error("场次放号失败，等待下一轮恢复 slotId={}", slot.getSlotId(), e);
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepareDemoSlots() {
        // 演示环境随时重启都应有可操作场次；uk_date_hour 使它可安全重跑。
        generateDate(time.today());
        generateDate(time.today().plusDays(props.getSlot().getGenDaysAhead()));
        releaseDue();
    }

    public int generateDate(LocalDate date) {
        int generated = 0;
        for (SlotTemplate template : templateMapper.selectEnabled()) {
            Slot inserted = singleTx.execute(status -> insertSlot(date, template));
            if (inserted != null) {
                cacheMeta(inserted);
                generated++;
            }
        }
        if (generated > 0) {
            log.info("生成场次 date={} count={}", date, generated);
        }
        return generated;
    }

    private Slot insertSlot(LocalDate date, SlotTemplate template) {
        Slot slot = new Slot();
        slot.setSlotId(idGenerator.nextId());
        slot.setTemplateId(template.getTemplateId());
        slot.setSlotDate(date);
        slot.setSlotHour(template.getSlotHour());
        slot.setDurationMin(template.getDurationMin());
        slot.setValidUntil(time.validUntil(date, template.getSlotHour(), template.getDurationMin()));
        slot.setCapacity(template.getCapacity());
        slot.setBucketCount(template.getBucketCount());
        slot.setReleased(0);
        slot.setReleaseAt(time.releaseAt(date, template.getReleaseOffsetMin()));
        slot.setVersion(0);
        if (slotMapper.insertIgnore(slot) == 0) {
            return null;
        }
        bucketMapper.batchInsertIgnore(splitBuckets(slot));
        return slot;
    }

    private void release(Slot slot) {
        RLock lock = capacityLock(slot.getSlotId());
        lock.lock();
        try {
            if (slotMapper.casRelease(slot.getSlotId(), slot.getVersion()) != 1) {
                return;
            }
            slot.setReleased(1);
            slot.setVersion(slot.getVersion() + 1);
            List<SlotBucket> buckets = bucketMapper.selectBySlot(slot.getSlotId());
            initializeBuckets(slot, buckets, false);
            cacheMeta(slot);
            log.info("场次放号 slotId={} date={} hour={} capacity={}",
                    slot.getSlotId(), slot.getSlotDate(), slot.getSlotHour(), slot.getCapacity());
        } finally {
            lock.unlock();
        }
    }

    private void initializeBuckets(Slot slot, List<SlotBucket> buckets, boolean fromOccupied) {
        List<Object> keys = buckets.stream().map(b -> bucketKey(slot.getSlotId(), b.getBucketNo()))
                .map(Object.class::cast).toList();
        List<Object> argv = new ArrayList<>(buckets.size() + 3);
        for (SlotBucket bucket : buckets) {
            int remaining = fromOccupied ? bucket.getTotal() - bucket.getOccupied() : bucket.getTotal();
            argv.add(Integer.toString(Math.max(0, remaining)));
        }
        argv.add(Long.toString(slot.getSlotId()));
        argv.add(Long.toString(time.ttlUntilEndOfDay(
                slot.getSlotDate(), props.getRedisKey().getDupTtlCapDays())));
        argv.add(Integer.toString(slot.getVersion()));
        lua.evalLong(LuaScripts.Script.RELEASE, keys, argv.toArray());
    }

    private void repairReleasedCaches() {
        LocalDate first = time.today();
        LocalDate last = first.plusDays(props.getSlot().getGenDaysAhead());
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            for (Slot slot : slotMapper.selectByDate(date)) {
                if (slot.getReleased() != 1) {
                    continue;
                }
                repairReleasedCache(slot);
            }
        }
    }

    private void repairReleasedCache(Slot slot) {
        RLock lock = capacityLock(slot.getSlotId());
        lock.lock();
        try {
            List<SlotBucket> buckets = bucketMapper.selectBySlot(slot.getSlotId());
            List<String> values = bucketValues(slot);
            long present = values.stream().filter(java.util.Objects::nonNull).count();
            if (present == 0) {
                initializeBuckets(slot, buckets, true);
                log.warn("恢复缺失的 Redis 场次库存 slotId={}", slot.getSlotId());
            } else if (present != slot.getBucketCount()) {
                log.error("Redis 场次库存仅部分存在，拒绝覆盖 slotId={} present={}/{}",
                        slot.getSlotId(), present, slot.getBucketCount());
                return;
            } else if (!syncCapacityVersion(slot, buckets, values)) {
                return;
            }
            cacheMeta(slot);
        } finally {
            lock.unlock();
        }
    }

    public List<SlotView> listSlots(LocalDate date) {
        return slotMapper.selectByDate(date).stream().map(this::toView).toList();
    }

    public SlotView getSlot(long slotId) {
        return toView(loadExisting(slotId));
    }

    public void ensureCached(long slotId) {
        cacheMeta(loadExisting(slotId));
    }

    /**
     * 按主键取 slot,不存在则抛 {@code SLOT_NOT_FOUND} 并写空值缓存(05 §1.3)。
     *
     * <p>为什么必须有这道缓存:{@code GET /api/slots/{slotId}} **不需要登录**,而边缘
     * (Caddy)按设计不做限流 —— 没有它时,拿随机 slotId 刷这个接口就是"每请求一次主键
     * SELECT",三个连接池加起来只有 70 条连接。这是 05 §1.3 写下却一直没落地的一条。
     *
     * <p>⚠️ 标记**不能**写在 {@code slot:meta:{slotId}} 上(那是 Hash,写 String 会让
     * 后续 {@code HGETALL} 报 WRONGTYPE,把"场次不存在"变成 500,且 {@code cacheMeta}
     * 的 {@code HSET} 也会失败)。故用独立 key,语义仍与 {@code slot:full} 分开。
     */
    private Slot loadExisting(long slotId) {
        if (Boolean.TRUE.equals(redis.hasKey(absentKey(slotId)))) {
            throw BizException.of(ErrorCode.SLOT_NOT_FOUND);
        }
        Slot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            long ttl = Math.max(1L, props.getRedisKey().getAbsentTtlSec());
            redis.opsForValue().set(absentKey(slotId), "1", Duration.ofSeconds(ttl));
            throw BizException.of(ErrorCode.SLOT_NOT_FOUND);
        }
        return slot;
    }

    public List<AdminSlotView> listAdminSlots(LocalDate date) {
        return slotMapper.selectByDate(date).stream().map(this::toAdminView).toList();
    }

    public AdminSlotView getAdminSlot(long slotId) {
        Slot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw BizException.of(ErrorCode.SLOT_NOT_FOUND);
        }
        return toAdminView(slot);
    }

    /** Replaces the exposed capacity representation while preserving the business rule "increase only". */
    public AdminSlotView setCapacity(long slotId, int capacity,
                                     HttpPreconditions.VersionCondition condition,
                                     long operatorId) {
        RLock lock = capacityLock(slotId);
        lock.lock();
        try {
            Slot slot = slotMapper.selectById(slotId);
            if (slot == null) {
                throw BizException.of(ErrorCode.SLOT_NOT_FOUND);
            }
            if (!condition.matches(slot.getVersion())) {
                throw BizException.of(ErrorCode.PRECONDITION_FAILED);
            }
            int delta = capacity - slot.getCapacity();
            if (delta < 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "场次容量只允许增加");
            }
            if (delta > 0) {
                increaseCapacityLocked(slotId, delta, slot.getVersion(), operatorId);
                slot = slotMapper.selectById(slotId);
            }
            return toAdminView(slot);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 增容(只增不减,09 约束一)。
     *
     * <p>三件同步(缺一即不一致):① DB slot.capacity CAS ② slot_bucket.total 逐桶加
     * (按余数规则算逐桶增量,复用 splitBuckets 同款逻辑)③ Redis incr.lua 逐桶 INCRBY +
     * DEL slot:full + HSET slot:meta capacity。顺序:DB CAS 成功才碰 Redis
     * (CAS 失败抛并发冲突,不碰 Redis)。Redis 用容量 version 幂等追赶 DB，失败时明确返回降级，
     * 下一轮 cache repair 可安全重放。
     *
     * @throws BizException BAD_REQUEST 当 delta<=0 或 version 冲突
     */
    public void increaseCapacity(long slotId, int delta, int version) {
        RLock lock = capacityLock(slotId);
        lock.lock();
        try {
            increaseCapacityLocked(slotId, delta, version, null);
        } finally {
            lock.unlock();
        }
    }

    private void increaseCapacityLocked(long slotId, int delta, int version, Long operatorId) {
        if (delta <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "增容数量必须为正整数");
        }
        Slot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw BizException.of(ErrorCode.SLOT_NOT_FOUND);
        }
        if (slot.getReleased() != 1) {
            throw new BizException(ErrorCode.SLOT_NOT_RELEASED, "尚未放号,无法增容");
        }
        final int oldCapacity = slot.getCapacity();
        final int newCapacity = oldCapacity + delta;
        final List<SlotBucket> buckets = bucketMapper.selectBySlot(slotId);
        final List<Integer> perBucket = splitDelta(delta, slot.getBucketCount());
        validateBuckets(slot, buckets);
        if (!syncCapacityVersion(slot, buckets, bucketValues(slot))) {
            throw new BizException(ErrorCode.SERVICE_DEGRADED,
                    "Redis 容量版本不明确，已拒绝增容");
        }
        // DB 侧:capacity CAS + 逐桶 total 必须同一事务，否则中途缺桶会留下半套容量。
        singleTx.executeWithoutResult(status -> {
            if (slotMapper.casIncreaseCapacity(slotId, version, delta) != 1) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "场次已被其他管理员修改,请刷新后重试");
            }
            for (int i = 0; i < slot.getBucketCount(); i++) {
                int bucketNo = buckets.get(i).getBucketNo();
                if (bucketMapper.increaseTotal(slotId, bucketNo, perBucket.get(i)) != 1) {
                    throw new IllegalStateException("slot_bucket 不存在 slotId=" + slotId + " bucket=" + i);
                }
            }
            AuditLog audit = new AuditLog();
            audit.setId(idGenerator.nextId());
            audit.setOperatorType("ADMIN");
            audit.setOperatorId(operatorId);
            audit.setAction("INCREASE_CAPACITY");
            audit.setTargetType("SLOT");
            audit.setTargetId(slotId);
            audit.setBefore("{\"capacity\":" + oldCapacity + "}");
            audit.setAfter("{\"capacity\":" + newCapacity + ",\"delta\":" + delta + "}");
            audit.setRequestId("admin-incr-" + slotId);
            audit.setCreateAt(time.now());
            if (auditMapper.insert(audit) != 1) {
                throw new IllegalStateException("增容审计写入失败 slotId=" + slotId);
            }
        });
        // Redis 侧用 version 门闩幂等；未知结果可由下一轮 repair 安全重试。
        try {
            applyCapacityDelta(slotId, buckets, perBucket, version, version + 1, newCapacity);
        } catch (RuntimeException e) {
            log.error("增容 Redis 同步失败,DB 已改 {},等待 repair 幂等追赶 slotId={} delta={}",
                    newCapacity, slotId, delta, e);
            throw new BizException(ErrorCode.SERVICE_DEGRADED,
                    "数据库已增容，Redis 正在恢复，请稍后刷新");
        }
        log.info("增容 slotId={} {}→{}", slotId, oldCapacity, newCapacity);
    }

    private AdminSlotView toAdminView(Slot slot) {
        return new AdminSlotView(slot.getSlotId(), slot.getTemplateId(), slot.getSlotDate(),
                slot.getSlotHour(), slot.getDurationMin(), slot.getValidUntil(), slot.getCapacity(),
                slot.getBucketCount(), slot.getReleased() == 1, slot.getReleaseAt(),
                slot.getVersion(), remain(slot),
                Boolean.TRUE.equals(redis.hasKey(metaKey(slot.getSlotId()))));
    }

    /**
     * 放号监控:今日+次日场次的 DB 状态 + Redis meta 完整度 + 桶 present 数。
     */
    public List<ReleaseMonitorView> listReleaseMonitor() {
        List<ReleaseMonitorView> result = new ArrayList<>();
        for (LocalDate date = time.today(); !date.isAfter(time.today().plusDays(1)); date = date.plusDays(1)) {
            for (Slot slot : slotMapper.selectByDate(date)) {
                Map<Object, Object> meta = redis.opsForHash().entries(metaKey(slot.getSlotId()));
                boolean metaComplete = !meta.isEmpty()
                        && meta.containsKey("released") && meta.containsKey("slot_hour")
                        && meta.containsKey("valid_until") && meta.containsKey("capacity")
                        && meta.containsKey("bucket_count") && meta.containsKey("slot_date")
                        && meta.containsKey("release_at");
                List<String> values = bucketValues(slot);
                long present = values.stream().filter(java.util.Objects::nonNull).count();
                int redisRemain = remain(slot);
                result.add(new ReleaseMonitorView(slot.getSlotId(), slot.getSlotDate(),
                        slot.getSlotHour(), slot.getReleased() == 1, slot.getCapacity(),
                        slot.getBucketCount(), slot.getVersion(), metaComplete,
                        (int) present, slot.getBucketCount(), redisRemain,
                        time.toEpochSecond(slot.getReleaseAt())));
            }
        }
        return result;
    }

    /** 按 03 §4.2 余数规则把 delta 拆成逐桶增量(前 delta%N 桶各多 1)。
     *  包级可见供单测覆盖(与 {@link #splitBuckets} 同款规则,改一处必须两处同步)。 */
    static List<Integer> splitDelta(int delta, int bucketCount) {
        int base = delta / bucketCount;
        int rem = delta % bucketCount;
        List<Integer> per = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            per.add(base + (i < rem ? 1 : 0));
        }
        return per;
    }

    private SlotView toView(Slot slot) {
        if (!Boolean.TRUE.equals(redis.hasKey(metaKey(slot.getSlotId())))) {
            cacheMeta(slot);
        }
        int remain = slot.getReleased() == 1 ? remain(slot) : slot.getCapacity();
        return new SlotView(slot.getSlotId(), slot.getSlotDate(), slot.getSlotHour(),
                slot.getDurationMin(), slot.getReleased() == 1, time.toEpochSecond(slot.getReleaseAt()),
                slot.getValidUntil(), remain, slot.getReleased() == 1 && remain <= 0);
    }

    private int remain(Slot slot) {
        List<String> values = bucketValues(slot);
        int total = 0;
        for (String value : values) {
            if (value != null) {
                total += Integer.parseInt(value);
            }
        }
        return total;
    }

    private List<String> bucketValues(Slot slot) {
        List<String> keys = new ArrayList<>(slot.getBucketCount());
        for (int i = 0; i < slot.getBucketCount(); i++) {
            keys.add(bucketKey(slot.getSlotId(), i));
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        return values == null ? java.util.Collections.nCopies(slot.getBucketCount(), null) : values;
    }

    private boolean syncCapacityVersion(Slot slot, List<SlotBucket> buckets, List<String> values) {
        String key = capacityVersionKey(slot.getSlotId());
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            if (!matchesDatabaseRemaining(slot, buckets, values)) {
                log.error("Redis 容量版本缺失且桶余量与 DB 不符 slotId={}", slot.getSlotId());
                return false;
            }
            Duration ttl = Duration.ofSeconds(Math.max(1L, time.ttlUntilEndOfDay(
                    slot.getSlotDate(), props.getRedisKey().getDupTtlCapDays())));
            redis.opsForValue().setIfAbsent(key, Integer.toString(slot.getVersion()), ttl);
            raw = redis.opsForValue().get(key);
        }
        int applied;
        try {
            applied = Integer.parseInt(raw);
        } catch (RuntimeException e) {
            log.error("Redis 容量版本非法 slotId={} value={}", slot.getSlotId(), raw);
            return false;
        }
        if (applied == slot.getVersion()) {
            if (values.size() != slot.getBucketCount()
                    || values.stream().anyMatch(java.util.Objects::isNull)
                    || redis.getExpire(key, TimeUnit.MILLISECONDS) <= 0) {
                log.error("Redis 容量门闩或桶不完整 slotId={}", slot.getSlotId());
                return false;
            }
            if (!matchesDatabaseRemaining(slot, buckets, values)) {
                log.error("Redis/DB 逐桶余量不一致 slotId={}", slot.getSlotId());
                return false;
            }
            return true;
        }
        if (applied != slot.getVersion() - 1) {
            log.error("Redis/DB 容量版本跨级 slotId={} redis={} db={}",
                    slot.getSlotId(), applied, slot.getVersion());
            return false;
        }
        Object metaCapacity = redis.opsForHash().get(metaKey(slot.getSlotId()), "capacity");
        if (metaCapacity == null) {
            return false;
        }
        int delta;
        try {
            delta = slot.getCapacity() - Integer.parseInt(metaCapacity.toString());
        } catch (NumberFormatException e) {
            return false;
        }
        if (delta <= 0) {
            return false;
        }
        applyCapacityDelta(slot.getSlotId(), buckets, splitDelta(delta, slot.getBucketCount()),
                applied, slot.getVersion(), slot.getCapacity());
        log.warn("追赶未完成增容 slotId={} delta={} version={}→{}",
                slot.getSlotId(), delta, applied, slot.getVersion());
        return true;
    }

    private boolean matchesDatabaseRemaining(Slot slot, List<SlotBucket> buckets,
                                             List<String> values) {
        if (buckets.size() != slot.getBucketCount() || values.size() != slot.getBucketCount()) {
            return false;
        }
        for (int i = 0; i < slot.getBucketCount(); i++) {
            SlotBucket bucket = buckets.get(i);
            if (!Integer.valueOf(i).equals(bucket.getBucketNo()) || values.get(i) == null) {
                return false;
            }
            try {
                if (Integer.parseInt(values.get(i)) != bucket.getTotal() - bucket.getOccupied()) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static void validateBuckets(Slot slot, List<SlotBucket> buckets) {
        if (buckets.size() != slot.getBucketCount()) {
            throw new IllegalStateException("slot_bucket 数量不完整 slotId=" + slot.getSlotId());
        }
        for (int i = 0; i < slot.getBucketCount(); i++) {
            if (!Integer.valueOf(i).equals(buckets.get(i).getBucketNo())) {
                throw new IllegalStateException("slot_bucket 编号不连续 slotId=" + slot.getSlotId());
            }
        }
    }

    private void applyCapacityDelta(long slotId, List<SlotBucket> buckets, List<Integer> deltas,
                                    int expectedVersion, int newVersion, int newCapacity) {
        List<Object> keys = buckets.stream()
                .map(b -> (Object) bucketKey(slotId, b.getBucketNo())).toList();
        List<Object> argv = new ArrayList<>(deltas.size() + 4);
        for (Integer delta : deltas) {
            argv.add(Integer.toString(delta));
        }
        argv.add(Long.toString(slotId));
        argv.add(Integer.toString(expectedVersion));
        argv.add(Integer.toString(newVersion));
        argv.add(Integer.toString(newCapacity));
        Long result = lua.evalLong(LuaScripts.Script.INCR, keys, argv.toArray());
        if (!Long.valueOf(1L).equals(result) && !Long.valueOf(2L).equals(result)) {
            throw new IllegalStateException("Redis 容量 version 不匹配 slotId=" + slotId);
        }
    }

    private RLock capacityLock(long slotId) {
        return redisson.getLock("lock:slot:capacity:" + slotId);
    }

    private static String capacityVersionKey(long slotId) {
        return "slot:capacity:version:" + slotId;
    }

    private void cacheMeta(Slot slot) {
        // 场次已存在 → 清掉可能存在的空值标记。漏这一步会让"刚生成的场次"被自己的
        // 空值缓存挡住最长 absent-ttl-sec 秒,而现象是列表里有、点进去 404。
        redis.delete(absentKey(slot.getSlotId()));
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("released", Integer.toString(slot.getReleased()));
        meta.put("release_at", Long.toString(time.toEpochSecond(slot.getReleaseAt())));
        meta.put("slot_hour", Integer.toString(slot.getSlotHour()));
        meta.put("valid_until", Long.toString(time.toEpochSecond(slot.getValidUntil())));
        meta.put("capacity", Integer.toString(slot.getCapacity()));
        meta.put("bucket_count", Integer.toString(slot.getBucketCount()));
        meta.put("slot_date", slot.getSlotDate().toString());
        String key = metaKey(slot.getSlotId());
        redis.opsForHash().putAll(key, meta);
        long jitter = props.getRedisKey().getMetaTtlJitterSec() <= 0 ? 0
                : ThreadLocalRandom.current().nextLong(props.getRedisKey().getMetaTtlJitterSec() + 1);
        long untilDayEnd = Duration.between(time.now(), time.endOfDay(slot.getSlotDate())).getSeconds();
        long ttl = Math.max(props.getRedisKey().getMetaTtlBaseSec(), untilDayEnd) + jitter;
        redis.expire(key, Duration.ofSeconds(Math.max(1, ttl)));
    }

    static List<SlotBucket> splitBuckets(Slot slot) {
        int base = slot.getCapacity() / slot.getBucketCount();
        int remainder = slot.getCapacity() % slot.getBucketCount();
        List<SlotBucket> buckets = new ArrayList<>(slot.getBucketCount());
        for (int i = 0; i < slot.getBucketCount(); i++) {
            SlotBucket bucket = new SlotBucket();
            bucket.setSlotId(slot.getSlotId());
            bucket.setBucketNo(i);
            bucket.setTotal(base + (i < remainder ? 1 : 0));
            bucket.setOccupied(0);
            buckets.add(bucket);
        }
        return buckets;
    }

    private static String metaKey(long slotId) {
        return "slot:meta:" + slotId;
    }

    /** 空值缓存 key(05 §1.3)。独立于 Hash 的 {@code slot:meta:*},见 {@link #loadExisting}。 */
    private static String absentKey(long slotId) {
        return "slot:meta:absent:" + slotId;
    }

    private static String bucketKey(long slotId, int bucketNo) {
        return "slot:" + slotId + ":b:" + bucketNo;
    }

    public record SlotView(Long slotId, LocalDate slotDate, Integer slotHour, Integer durationMin,
                           boolean released, long releaseAt, LocalDateTime validUntil,
                           int remain, boolean full) {
    }

    public record AdminSlotView(Long slotId, Long templateId, LocalDate slotDate, Integer slotHour,
                                Integer durationMin, LocalDateTime validUntil, Integer capacity,
                                Integer bucketCount, boolean released, LocalDateTime releaseAt,
                                Integer version, int remain, boolean metaPresent) {
    }

    public record ReleaseMonitorView(Long slotId, LocalDate slotDate, Integer slotHour,
                                      boolean released, Integer capacity, Integer bucketCount,
                                      Integer version, boolean metaComplete, int bucketPresent,
                                      int bucketExpected, int redisRemain, long releaseAt) {
    }
}
