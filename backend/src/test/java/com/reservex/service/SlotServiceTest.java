package com.reservex.service;

import com.reservex.entity.Slot;
import com.reservex.entity.SlotBucket;
import com.reservex.entity.AuditLog;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.id.IdGenerator;
import com.reservex.lua.LuaScripts;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.SlotBucketMapper;
import com.reservex.mapper.single.SlotMapper;
import com.reservex.mapper.single.SlotTemplateMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotServiceTest {

    @Test
    void templateCannotOutliveItsRedisInventoryDay() {
        assertThatThrownBy(() -> SlotTemplateAdminService.validate(23, 120, 50, 10, -60))
                .isInstanceOf(com.reservex.common.BizException.class);
    }

    @Test
    void templateBoundsPreventPersistentGenerationOom() {
        assertThatThrownBy(() -> SlotTemplateAdminService.validate(9, 120, 100_001, 10, -60))
                .isInstanceOf(com.reservex.common.BizException.class);
        assertThatThrownBy(() -> SlotTemplateAdminService.validate(9, 120, 2_000, 1_001, -60))
                .isInstanceOf(com.reservex.common.BizException.class);
        assertThatThrownBy(() -> SlotTemplateAdminService.validate(9, 120, 50, 10, -1_441))
                .isInstanceOf(com.reservex.common.BizException.class);
    }

    /**
     * 05 §1.3 空值缓存。{@code GET /api/slots/{slotId}} 无需登录、边缘不限流,
     * 没有这道缓存时拿随机 slotId 刷它就是"每请求一次主键 SELECT"。
     * 断言第二次查询**不再碰 DB**,而不是只断言"两次都 404"(那样漏掉缓存没生效的情况)。
     */
    @Test
    void absentSlotIsCachedSoRepeatedLookupsDoNotReachTheDatabase() {
        SlotMapper slots = mock(SlotMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(slots.selectById(404L)).thenReturn(null);
        // 首次:标记不存在 → 落到 DB → DB 也没有 → 写标记
        when(redis.hasKey("slot:meta:absent:404")).thenReturn(false);
        SlotService service = serviceWith(slots, redis);

        assertThatThrownBy(() -> service.getSlot(404L))
                .isInstanceOf(com.reservex.common.BizException.class);
        verify(slots).selectById(404L);
        verify(values).set("slot:meta:absent:404", "1", java.time.Duration.ofSeconds(60));

        // 第二次:标记已在 → 必须直接抛,不得再查 DB
        when(redis.hasKey("slot:meta:absent:404")).thenReturn(true);
        assertThatThrownBy(() -> service.getSlot(404L))
                .isInstanceOf(com.reservex.common.BizException.class);
        verify(slots, org.mockito.Mockito.times(1)).selectById(404L);
    }

    /** 场次真实存在时必须清掉空值标记,否则新生成的场次被自己的缓存挡住(列表有、详情 404)。 */
    @Test
    void cachingRealSlotClearsTheAbsentMarker() {
        SlotMapper slots = mock(SlotMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.opsForHash()).thenReturn(mock(org.springframework.data.redis.core.HashOperations.class));
        when(redis.hasKey("slot:meta:absent:7")).thenReturn(false);
        Slot slot = new Slot();
        slot.setSlotId(7L);
        slot.setReleased(1);
        slot.setReleaseAt(java.time.LocalDateTime.now());
        slot.setValidUntil(java.time.LocalDateTime.now().plusHours(2));
        slot.setSlotHour(9);
        slot.setCapacity(50);
        slot.setBucketCount(10);
        slot.setSlotDate(java.time.LocalDate.now());
        when(slots.selectById(7L)).thenReturn(slot);

        serviceWith(slots, redis).ensureCached(7L);

        verify(redis).delete("slot:meta:absent:7");
    }

    private static SlotService serviceWith(SlotMapper slots, StringRedisTemplate redis) {
        ReserveXProperties props = new ReserveXProperties();
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new SlotService(mock(SlotTemplateMapper.class), slots, mock(SlotBucketMapper.class),
                mock(AuditLogMapper.class), mock(IdGenerator.class), new TimeSupport(props), props,
                mock(LuaScripts.class), redis, mock(RedissonClient.class), tx);
    }

    @Test
    void bucketSplitPreservesCapacityAndRemainderOrder() {
        Slot slot = new Slot();
        slot.setSlotId(1L);
        slot.setCapacity(55);
        slot.setBucketCount(10);

        List<SlotBucket> buckets = SlotService.splitBuckets(slot);

        assertThat(buckets).extracting(SlotBucket::getTotal)
                .containsExactly(6, 6, 6, 6, 6, 5, 5, 5, 5, 5);
        assertThat(buckets.stream().mapToInt(SlotBucket::getTotal).sum()).isEqualTo(55);
    }

    /**
     * D5/D8:增容逐桶增量必须与 splitBuckets 用同一款余数规则(03 §4.2),
     * 否则增容后 {@code Σ total < capacity + delta},库存不变量
     * {@code C = A + R + V + X} 永久带一个固定差,对账每轮报同样的 diff 且查不出原因。
     * 本测试钉死 splitDelta 与 splitBuckets 的余数分配顺序一致(前 rem 桶各多 1)。
     */
    @Test
    void splitDeltaMatchesSplitBucketsRemainderRule() {
        // 整除:每桶均分,无余数,前后桶相同。
        List<Integer> even = SlotService.splitDelta(20, 10);
        assertThat(even).containsExactly(2, 2, 2, 2, 2, 2, 2, 2, 2, 2);
        assertThat(even.stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);

        // 余 5:前 5 桶各多 1,后 5 桶为 base —— 与 splitBuckets(55,10) 的余数分配同序。
        List<Integer> rem = SlotService.splitDelta(15, 10);
        assertThat(rem).containsExactly(2, 2, 2, 2, 2, 1, 1, 1, 1, 1);
        assertThat(rem.stream().mapToInt(Integer::intValue).sum()).isEqualTo(15);

        // delta < bucketCount:base=0,前 delta 个桶各 1,其余 0。
        List<Integer> small = SlotService.splitDelta(3, 10);
        assertThat(small).containsExactly(1, 1, 1, 0, 0, 0, 0, 0, 0, 0);
        assertThat(small.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);

        // 余数 0 边界 + 桶数 1:单桶全量。
        assertThat(SlotService.splitDelta(7, 1)).containsExactly(7);
    }

    /**
     * splitDelta 与 splitBuckets 必须对同一余数算出相同的前缀长度 ——
     * 这是"两处规则只应有一处实现"的不变量:若有人把 splitDelta 改成"后 rem 桶各多 1",
     * 增容时补的桶与放号时初始分的桶就会错位,Σ 对得上而单桶对不上,是最难查的不一致。
     */
    @Test
    void splitDeltaAndSplitBucketsSameRemainderPrefix() {
        // capacity=50(整除 rem=0)+ delta=15(rem=5)。
        // splitBuckets 全桶 5(无余数);splitDelta 前 5 桶 2、后 5 桶 1。
        // 合并:前 5 桶 5+2=7,后 5 桶 5+1=6 —— 前缀多出来的是 splitDelta 的 rem,
        // 且 rem 落在前缀(与 splitBuckets 的"前 rem 桶各多 1"同序),不能错位。
        int bucketCount = 10;
        int capacity = 50;
        int delta = 15;
        Slot slot = new Slot();
        slot.setCapacity(capacity);
        slot.setBucketCount(bucketCount);
        List<SlotBucket> buckets = SlotService.splitBuckets(slot);
        List<Integer> deltas = SlotService.splitDelta(delta, bucketCount);

        for (int i = 0; i < bucketCount; i++) {
            int combined = buckets.get(i).getTotal() + deltas.get(i);
            if (i < 5) {
                assertThat(combined).as("前 %d 桶(splitBuckets base + splitDelta base+1)", 5).isEqualTo(7);
            } else {
                assertThat(combined).as("后 %d 桶(splitBuckets base + splitDelta base)", bucketCount - 5).isEqualTo(6);
            }
        }
    }

    @Test
    void increaseCapacityRollsBackWhenAnyBucketIsMissing() {
        SlotMapper slots = mock(SlotMapper.class);
        SlotBucketMapper buckets = mock(SlotBucketMapper.class);
        LuaScripts lua = mock(LuaScripts.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        Slot slot = new Slot();
        slot.setSlotId(1L);
        slot.setReleased(1);
        slot.setCapacity(10);
        slot.setBucketCount(2);
        SlotBucket first = new SlotBucket();
        first.setBucketNo(0);
        when(slots.selectById(1L)).thenReturn(slot);
        when(slots.casIncreaseCapacity(1L, 0, 2)).thenReturn(1);
        when(buckets.selectBySlot(1L)).thenReturn(List.of(first));
        when(buckets.increaseTotal(1L, 0, 1)).thenReturn(1);

        SlotService service = new SlotService(mock(SlotTemplateMapper.class), slots, buckets,
                mock(AuditLogMapper.class), mock(IdGenerator.class), mock(TimeSupport.class),
                new ReserveXProperties(), lua, mock(StringRedisTemplate.class), redisson, tx);

        assertThatThrownBy(() -> service.increaseCapacity(1L, 2, 0))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(tx, lua);
    }

    @Test
    void increaseCapacityPassesVersionFenceToLua() {
        SlotMapper slots = mock(SlotMapper.class);
        SlotBucketMapper buckets = mock(SlotBucketMapper.class);
        AuditLogMapper audits = mock(AuditLogMapper.class);
        LuaScripts lua = mock(LuaScripts.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(mock(RLock.class));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        Slot slot = new Slot();
        slot.setSlotId(1L);
        slot.setReleased(1);
        slot.setCapacity(10);
        slot.setBucketCount(2);
        slot.setVersion(1);
        SlotBucket first = bucket(0, 5);
        SlotBucket second = bucket(1, 5);
        when(slots.selectById(1L)).thenReturn(slot);
        when(slots.casIncreaseCapacity(1L, 1, 3)).thenReturn(1);
        when(buckets.selectBySlot(1L)).thenReturn(List.of(first, second));
        when(buckets.increaseTotal(1L, 0, 2)).thenReturn(1);
        when(buckets.increaseTotal(1L, 1, 1)).thenReturn(1);
        when(audits.insert(any(AuditLog.class))).thenReturn(1);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(List.of("slot:1:b:0", "slot:1:b:1")))
                .thenReturn(List.of("5", "5"));
        when(values.get("slot:capacity:version:1")).thenReturn("1");
        when(redis.getExpire("slot:capacity:version:1", TimeUnit.MILLISECONDS)).thenReturn(60_000L);
        when(lua.evalLong(LuaScripts.Script.INCR,
                List.of("slot:1:b:0", "slot:1:b:1"),
                "2", "1", "1", "1", "2", "13")).thenReturn(1L);

        SlotService service = new SlotService(mock(SlotTemplateMapper.class), slots, buckets,
                audits, mock(IdGenerator.class), mock(TimeSupport.class),
                new ReserveXProperties(), lua, redis, redisson, tx);
        service.increaseCapacity(1L, 3, 1);

        verify(lua).evalLong(LuaScripts.Script.INCR,
                List.of("slot:1:b:0", "slot:1:b:1"),
                "2", "1", "1", "1", "2", "13");
    }

    @Test
    void increaseCapacityRejectsMissingRedisBucketBeforeDatabaseWrite() {
        SlotMapper slots = mock(SlotMapper.class);
        SlotBucketMapper buckets = mock(SlotBucketMapper.class);
        LuaScripts lua = mock(LuaScripts.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(mock(RLock.class));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

        Slot slot = new Slot();
        slot.setSlotId(1L);
        slot.setReleased(1);
        slot.setCapacity(10);
        slot.setBucketCount(2);
        slot.setVersion(1);
        when(slots.selectById(1L)).thenReturn(slot);
        when(buckets.selectBySlot(1L)).thenReturn(List.of(bucket(0, 5), bucket(1, 5)));

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(List.of("slot:1:b:0", "slot:1:b:1")))
                .thenReturn(java.util.Arrays.asList("5", null));
        when(values.get("slot:capacity:version:1")).thenReturn("1");

        SlotService service = new SlotService(mock(SlotTemplateMapper.class), slots, buckets,
                mock(AuditLogMapper.class), mock(IdGenerator.class), mock(TimeSupport.class),
                new ReserveXProperties(), lua, redis, redisson, tx);

        assertThatThrownBy(() -> service.increaseCapacity(1L, 2, 1))
                .isInstanceOf(com.reservex.common.BizException.class);
        verifyNoInteractions(tx, lua);
    }

    private static SlotBucket bucket(int number, int total) {
        SlotBucket bucket = new SlotBucket();
        bucket.setBucketNo(number);
        bucket.setTotal(total);
        bucket.setOccupied(0);
        return bucket;
    }
}
