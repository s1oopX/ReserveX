package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 场次(单库表)。由生成任务从 {@link SlotTemplate} **拷贝**字段产生。
 *
 * <p>⚠️ <b>{@code template_id} 只做溯源,不做外键、不做运行期引用。</b>
 * 模板改了不能追溯改变已生成的场次(03 §9.1):Redis 里的桶已按旧 capacity 初始化,
 * 若 slot 实时读模板,DB 与 Redis 会立刻对不上,而库存对账报出的 diff 无法修 ——
 * 因为"正确值"本身被改掉了。手工建的场次此列为 NULL。
 *
 * <p>⚠️ <b>{@code released} 的 0→1 CAS 是放号的唯一闸门。</b>10.3 Lua 用 {@code SET}
 * 覆盖写桶余量,看似幂等,但**重跑会覆盖掉已被抢掉的余量** = 凭空造库存。
 * 所以必须先 CAS,受影响 0 行即说明已放过号,**绝不能再跑 10.3**(04 §四)。
 * 这是"锁减概率、CAS 保正确"在放号链路的落点。
 *
 * <p>⚠️ <b>{@code bucket_count} 已放号禁改。</b>抢号按
 * {@code (hash(rno) & 0x7fffffff) % bucket_count} 路由,改了这个数,
 * 同一预约在取消/对账时会算出**另一个桶** → 回滚回补到错的桶上。管理端须拦在接口层
 * ({@code SLOT_RELEASED_IMMUTABLE})。
 *
 * <p>⚠️ <b>{@code valid_until} 落库固化</b>,不由 {@code slot_date + slot_hour +
 * duration_min} 实时算 —— 同上,模板改 duration 不应改变已发布场次的有效期。
 * 它同时是抢号 Lua 的 ARGV(写进 occupy 载荷)与过期扫描的判据。
 *
 * <p>⚠️ <b>{@code uk_date_hour} 挡生成任务重跑。</b>任务在 02:30 跑,若中断后重跑
 * (或人工补跑),冲突即跳过,不会造出第二套同日同时段场次。**"跳过"必须是
 * INSERT IGNORE 或捕获 DuplicateKey 后继续,不能让整批生成中断**(00 §6.2·补5 P0-16)。
 */
@Data
@TableName("slot")
public class Slot {

    @TableId(type = IdType.INPUT)
    private Long slotId;

    /** 来源模板,仅溯源。手工建的为 NULL。 */
    private Long templateId;

    private LocalDate slotDate;

    private Integer slotHour;

    /** {@code = slot_date + slot_hour:00 + duration_min},落库固化。见类注释。 */
    private LocalDateTime validUntil;

    private Integer durationMin;

    /** 总容量。增容只增不减,且必须与 Redis 逐桶 INCRBY + HSET slot:meta 三处同步。 */
    private Integer capacity;

    /** 分桶数。已放号禁改。 */
    private Integer bucketCount;

    /** 0 未放号,1 已放号。0→1 的 CAS 是放号闸门,见类注释。 */
    private Integer released;

    /** {@code = slot_date 00:00 + template.release_offset_min}。NOT NULL,生成时必须算出来填。 */
    private LocalDateTime releaseAt;

    /** 乐观锁。放号 CAS 与增容 CAS 都用它。 */
    private Integer version;
}
