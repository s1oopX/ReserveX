package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.SlotBucket;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分桶库存 Mapper(**单库**)。DB 侧账,对账的另一半。
 *
 * <p>⚠️ <b>复合主键 {@code (slot_id, bucket_no)} → 不能用 {@code selectById} /
 * {@code updateById}</b>(见 {@link SlotBucket} 类注释)。
 */
public interface SlotBucketMapper extends BaseMapper<SlotBucket> {

    /**
     * 放号时批量建桶。{@code total} 按余数规则分摊,**由调用方算好传入**。
     *
     * <p>⚠️ 分摊规则只应有一处实现({@code service} 层的工具方法),
     * 在 SQL 里再算一遍会出现两份规则,改了一处忘另一处 → Σ total ≠ capacity(03 §4.2)。
     */
    int batchInsertIgnore(@Param("buckets") List<SlotBucket> buckets);

    /**
     * 落库成功后累加占用。**只增不减**(M1 不返还)。
     *
     * <p>⚠️ 用 {@code occupied = occupied + 1} 而非先读后写 ——
     * 并发落库下读改写会丢更新,而这一列是对账的基准值,错了就查不出真相。
     */
    int incrOccupied(@Param("slotId") Long slotId, @Param("bucketNo") Integer bucketNo);

    /** 库存对账:取某场次全部桶。 */
    List<SlotBucket> selectBySlot(@Param("slotId") Long slotId);

    /** 增容:逐桶加 delta。与 Redis INCRBY、{@code slot.capacity} 三处同步。 */
    int increaseTotal(@Param("slotId") Long slotId,
                      @Param("bucketNo") Integer bucketNo,
                      @Param("delta") Integer delta);
}
