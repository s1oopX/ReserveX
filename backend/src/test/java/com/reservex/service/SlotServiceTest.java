package com.reservex.service;

import com.reservex.entity.Slot;
import com.reservex.entity.SlotBucket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlotServiceTest {

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
}
