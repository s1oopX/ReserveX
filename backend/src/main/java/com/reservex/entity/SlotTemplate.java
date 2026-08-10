package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 场次模板(单库表)—— 次日 {@code slot} 生成任务的**唯一数据来源**(03 §4.0)。
 *
 * <p><b>为什么必须是表而不是 yml。</b>{@code reservex.slot.seed.*} 只在
 * {@code 02-seed.sql} 灌种子时用一次;运行期读 yml 意味着:改容量要重启、
 * 四个时段无法差异化配置、运营改不了任何东西。**代码里凡是在业务路径读 {@code seed.*}
 * 的都是错的**(08 §7.1 红线)。
 *
 * <p>⚠️ <b>{@code release_offset_min} 存偏移不存时刻。</b>模板不绑定具体日期,
 * 它要能生成任意一天的场次。{@code -840} = {@code slot_date 00:00 - 840min} = 前一日 10:00。
 * 若存绝对时刻,模板就只对某一天有效,次日生成任务无从下手。
 *
 * <p>⚠️ <b>{@code enabled=0} 是停用,不是删除。</b>历史 {@code slot.template_id}
 * 仍指向它(做溯源)。删行会让历史场次的来源变成悬空引用,而运营的真实意图
 * "这个时段以后不开了"并不要求抹掉历史。
 *
 * <p>⚠️ <b>改模板不影响已生成的场次</b>(copy-not-reference,03 §9.1)。
 * 生成任务是把模板字段**拷贝**进 {@code slot},不是让 slot 引用模板 ——
 * 否则运营今天改容量会追溯改变昨天已放号场次的库存,而 Redis 里的桶早已按旧值初始化,
 * DB 与 Redis 立刻对不上。
 *
 * <p>⚠️ <b>{@code uk_hour}:v1 一个时段一个模板。</b>生成任务重跑时冲突即跳过,
 * 不会造出第二套 9 点场。多套模板并存是 v2 的事。
 */
@Data
@TableName("slot_template")
public class SlotTemplate {

    /** Snowflake。seed 里固定 1~4(Snowflake 起始远大于此,永不冲突)。 */
    @TableId(type = IdType.INPUT)
    private Long templateId;

    /** 时段起始小时(9/11/14/16)。 */
    private Integer slotHour;

    private Integer durationMin;

    private Integer capacity;

    /** 分桶数。生成 slot 时拷贝过去,**已放号的 slot 禁改**(改则 hash 路由错位)。 */
    private Integer bucketCount;

    /** 相对 {@code slot_date 00:00} 的分钟偏移。见类注释。 */
    private Integer releaseOffsetMin;

    /** 0 停用(生成任务跳过),1 启用。 */
    private Integer enabled;

    private LocalDateTime createAt;

    private LocalDateTime updateAt;

    /** 乐观锁:管理端并发改模板时防覆盖。 */
    private Integer version;
}
