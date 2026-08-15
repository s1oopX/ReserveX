package com.reservex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
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

/** 场次模板、生成、放号和查询。 */
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
        this.singleTx = new TransactionTemplate(txManager);
    }

    public List<TemplateView> listTemplates() {
        return templateMapper.selectList(new LambdaQueryWrapper<SlotTemplate>()
                        .orderByAsc(SlotTemplate::getSlotHour))
                .stream().map(TemplateView::from).toList();
    }

    public TemplateView createTemplate(TemplateInput input) {
        validateTemplate(input.slotHour(), input.durationMin(), input.capacity(),
                input.bucketCount(), input.releaseOffsetMin());
        LocalDateTime now = time.now();
        SlotTemplate template = new SlotTemplate();
        template.setTemplateId(idGenerator.nextId());
        apply(template, input);
        template.setEnabled(input.enabled() ? 1 : 0);
        template.setCreateAt(now);
        template.setUpdateAt(now);
        template.setVersion(0);
        try {
            templateMapper.insert(template);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.TEMPLATE_INVALID, "该时段已有模板");
        }
        return TemplateView.from(template);
    }

    public TemplateView updateTemplate(long templateId, TemplatePatch patch) {
        SlotTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (patch.version() == null || patch.version() != template.getVersion()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模板已被其他管理员修改，请刷新后重试");
        }
        TemplateInput merged = new TemplateInput(
                patch.slotHour() == null ? template.getSlotHour() : patch.slotHour(),
                patch.durationMin() == null ? template.getDurationMin() : patch.durationMin(),
                patch.capacity() == null ? template.getCapacity() : patch.capacity(),
                patch.bucketCount() == null ? template.getBucketCount() : patch.bucketCount(),
                patch.releaseOffsetMin() == null ? template.getReleaseOffsetMin() : patch.releaseOffsetMin(),
                patch.enabled() == null ? template.getEnabled() == 1 : patch.enabled());
        validateTemplate(merged.slotHour(), merged.durationMin(), merged.capacity(),
                merged.bucketCount(), merged.releaseOffsetMin());
        if (merged.slotHour() != template.getSlotHour()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模板时段不可修改，请停用后新建");
        }
        apply(template, merged);
        template.setEnabled(merged.enabled() ? 1 : 0);
        template.setUpdateAt(time.now());
        if (templateMapper.casUpdate(template) != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模板已被其他管理员修改，请刷新后重试");
        }
        template.setVersion(template.getVersion() + 1);
        return TemplateView.from(template);
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
        if (slotMapper.casRelease(slot.getSlotId(), slot.getVersion()) != 1) {
            return;
        }
        List<SlotBucket> buckets = bucketMapper.selectBySlot(slot.getSlotId());
        initializeBuckets(slot, buckets, false);
        slot.setReleased(1);
        slot.setVersion(slot.getVersion() + 1);
        cacheMeta(slot);
        log.info("场次放号 slotId={} date={} hour={} capacity={}",
                slot.getSlotId(), slot.getSlotDate(), slot.getSlotHour(), slot.getCapacity());
    }

    private void initializeBuckets(Slot slot, List<SlotBucket> buckets, boolean fromOccupied) {
        List<Object> keys = buckets.stream().map(b -> bucketKey(slot.getSlotId(), b.getBucketNo()))
                .map(Object.class::cast).toList();
        List<Object> argv = new ArrayList<>(buckets.size() + 2);
        for (SlotBucket bucket : buckets) {
            int remaining = fromOccupied ? bucket.getTotal() - bucket.getOccupied() : bucket.getTotal();
            argv.add(Integer.toString(Math.max(0, remaining)));
        }
        argv.add(Long.toString(slot.getSlotId()));
        argv.add(Long.toString(time.ttlUntilEndOfDay(
                slot.getSlotDate(), props.getRedisKey().getDupTtlCapDays())));
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
                List<String> values = bucketValues(slot);
                long present = values.stream().filter(java.util.Objects::nonNull).count();
                if (present == 0) {
                    initializeBuckets(slot, bucketMapper.selectBySlot(slot.getSlotId()), true);
                    log.warn("恢复缺失的 Redis 场次库存 slotId={}", slot.getSlotId());
                } else if (present != slot.getBucketCount()) {
                    log.error("Redis 场次库存仅部分存在，拒绝覆盖 slotId={} present={}/{}",
                            slot.getSlotId(), present, slot.getBucketCount());
                    continue;
                }
                cacheMeta(slot);
            }
        }
    }

    public List<SlotView> listSlots(LocalDate date) {
        return slotMapper.selectByDate(date).stream().map(this::toView).toList();
    }

    public SlotView getSlot(long slotId) {
        Slot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw BizException.of(ErrorCode.SLOT_NOT_FOUND);
        }
        return toView(slot);
    }

    public void ensureCached(long slotId) {
        Slot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw BizException.of(ErrorCode.SLOT_NOT_FOUND);
        }
        cacheMeta(slot);
    }

    public List<AdminSlotView> listAdminSlots(LocalDate date) {
        return slotMapper.selectByDate(date).stream().map(slot -> new AdminSlotView(
                slot.getSlotId(), slot.getTemplateId(), slot.getSlotDate(), slot.getSlotHour(),
                slot.getDurationMin(), slot.getValidUntil(), slot.getCapacity(), slot.getBucketCount(),
                slot.getReleased() == 1, slot.getReleaseAt(), slot.getVersion(), remain(slot),
                Boolean.TRUE.equals(redis.hasKey(metaKey(slot.getSlotId()))))).toList();
    }

    /**
     * 增容(只增不减,09 约束一)。
     *
     * <p>三件同步(缺一即不一致):① DB slot.capacity CAS ② slot_bucket.total 逐桶加
     * (按余数规则算逐桶增量,复用 splitBuckets 同款逻辑)③ Redis incr.lua 逐桶 INCRBY +
     * DEL slot:full + HSET slot:meta capacity。顺序:DB CAS 成功才碰 Redis
     * (CAS 失败抛并发冲突,不碰 Redis)。Redis 失败 DB 已改,靠对账 diff + log 暴露,不自动修
     * (与 stock-auto-fix=false 一致)。
     *
     * @throws BizException BAD_REQUEST 当 delta<=0 或 version 冲突
     */
    public void increaseCapacity(long slotId, int delta, int version) {
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
        // DB 侧:capacity CAS + 逐桶 total。CAS 失败抛 BAD_REQUEST(并发改)。
        int cas = slotMapper.casIncreaseCapacity(slotId, version, delta);
        if (cas != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "场次已被其他管理员修改,请刷新后重试");
        }
        for (int i = 0; i < buckets.size(); i++) {
            bucketMapper.increaseTotal(slotId, i, perBucket.get(i));
        }
        // Redis 侧:incr.lua 逐桶 INCRBY + DEL slot:full。失败不回滚 DB,靠对账暴露。
        try {
            List<Object> keys = buckets.stream()
                    .map(b -> (Object) bucketKey(slotId, b.getBucketNo())).toList();
            List<Object> argv = new ArrayList<>(perBucket.size() + 1);
            for (Integer d : perBucket) {
                argv.add(Integer.toString(d));
            }
            argv.add(Long.toString(slotId));
            lua.evalLong(LuaScripts.Script.INCR, keys, argv.toArray());
            redis.opsForHash().put(metaKey(slotId), "capacity", Integer.toString(newCapacity));
        } catch (RuntimeException e) {
            log.error("增容 Redis 同步失败,DB 已改 {},等待对账暴露 slotId={} delta={}",
                    newCapacity, slotId, delta, e);
        }
        // 审计
        AuditLog audit = new AuditLog();
        audit.setId(idGenerator.nextId());
        audit.setOperatorType("ADMIN");
        audit.setOperatorId(null);
        audit.setAction("INCREASE_CAPACITY");
        audit.setTargetType("slot");
        audit.setTargetId(slotId);
        audit.setBefore("{\"capacity\":" + oldCapacity + "}");
        audit.setAfter("{\"capacity\":" + newCapacity + ",\"delta\":" + delta + "}");
        audit.setRequestId("admin-incr-" + slotId);
        audit.setCreateAt(time.now());
        singleTx.executeWithoutResult(status -> auditMapper.insert(audit));
        log.info("增容 slotId={} {}→{}", slotId, oldCapacity, newCapacity);
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

    private void cacheMeta(Slot slot) {
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

    private static void validateTemplate(Integer hour, Integer duration, Integer capacity,
                                         Integer buckets, Integer releaseOffset) {
        if (hour == null || hour < 0 || hour > 23 || duration == null || duration <= 0
                || capacity == null || capacity <= 0 || buckets == null || buckets <= 0
                || capacity < buckets || releaseOffset == null || releaseOffset >= hour * 60) {
            throw BizException.of(ErrorCode.TEMPLATE_INVALID);
        }
    }

    private static void apply(SlotTemplate target, TemplateInput input) {
        target.setSlotHour(input.slotHour());
        target.setDurationMin(input.durationMin());
        target.setCapacity(input.capacity());
        target.setBucketCount(input.bucketCount());
        target.setReleaseOffsetMin(input.releaseOffsetMin());
    }

    private static String metaKey(long slotId) {
        return "slot:meta:" + slotId;
    }

    private static String bucketKey(long slotId, int bucketNo) {
        return "slot:" + slotId + ":b:" + bucketNo;
    }

    public record TemplateInput(Integer slotHour, Integer durationMin, Integer capacity,
                                Integer bucketCount, Integer releaseOffsetMin, boolean enabled) {
    }

    public record TemplatePatch(Integer slotHour, Integer durationMin, Integer capacity,
                                Integer bucketCount, Integer releaseOffsetMin, Boolean enabled,
                                Integer version) {
    }

    public record TemplateView(Long templateId, Integer slotHour, Integer durationMin,
                               Integer capacity, Integer bucketCount, Integer releaseOffsetMin,
                               boolean enabled, Integer version) {
        static TemplateView from(SlotTemplate template) {
            return new TemplateView(template.getTemplateId(), template.getSlotHour(),
                    template.getDurationMin(), template.getCapacity(), template.getBucketCount(),
                    template.getReleaseOffsetMin(), template.getEnabled() == 1, template.getVersion());
        }
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
