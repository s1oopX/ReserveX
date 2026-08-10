package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 场次分桶库存的 **DB 侧账**(单库表)。Redis 侧是真库存,本表是对账的另一半。
 *
 * <p>⚠️ <b>复合主键 {@code (slot_id, bucket_no)},MyBatis-Plus 不支持复合
 * {@code @TableId}</b> → 本类**不标注** {@code @TableId},靠 XML resultMap。
 * 后果:**不能用 {@code selectById} / {@code updateById}**,按主键的读写一律走
 * 自定义 XML 方法。误用 {@code updateById} 时 MyBatis-Plus 会抛"主键不存在",
 * 属于会变红的一类错,不致命。
 *
 * <p>⚠️ <b>{@code total} 不是 {@code capacity / bucket_count}。</b>按余数规则分摊:
 * {@code base = capacity / n},{@code rem = capacity % n},**前 rem 个桶各多 1**
 * (capacity=55, n=10 → 6,6,6,6,6,5,5,5,5,5,Σ=55)。若各桶同值,
 * {@code Σ total < capacity},库存不变量 {@code C = A + R + V + X} 会永久带一个固定差,
 * 而对账每轮报同样的 diff 且查不出原因(03 §4.2)。
 *
 * <p>⚠️ <b>{@code occupied} 只增不减。</b>它是**累计成功预约数**,不是"当前占用数"。
 * M1 决定取消/过期**不返还名额**,所以取消时既不 INCR Redis 桶,也不减本列 ——
 * 减回来就等于把名额放出去了,与 M1 直接冲突。
 *
 * <p>库存对账的三方比对(06 §四):
 * {@code capacity - Σ Redis 桶余量} ⟷ {@code Σ occupied} ⟷ {@code 有效预约数(RESERVED+VERIFIED)}。
 * 三者应恒等;第三方与前两方的差能区分"Redis 泄漏"与"落库丢失"两类故障。
 */
@Data
@TableName("slot_bucket")
public class SlotBucket {

    private Long slotId;

    /** 0 ~ {@code bucket_count - 1}。 */
    private Integer bucketNo;

    /** 该桶初始容量,按余数规则分摊。见类注释。 */
    private Integer total;

    /** 累计成功预约数,**取消/过期不减回**。见类注释。 */
    private Integer occupied;
}
