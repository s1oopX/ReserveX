package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 卡单表(单库表)。scanner 补投耗尽后**转人工**的落点(02 §3.2)。
 *
 * <p><b>为什么必须有这张表(而不是打条日志了事)。</b>回滚一笔卡单需要两个参数:
 * 桶 key(要 INCR 回补哪个桶)和 dup key(要 DEL 哪个配额位)。这两个值原本
 * **只存在于 Redis 的 occupy 载荷里**,而 occupy 有 TTL。TTL 一过,
 * 人工连"该回滚什么"都查不出来 —— 桶余量就此**永久泄漏**,
 * 表现为该场次永远少几个名额,且对账每轮都报同一个 diff 而无从修复。
 * 所以在 occupy 还活着的时候,必须把这两个参数**抄进持久存储**。
 *
 * <p>⚠️ <b>主键是 {@code reservation_no},这是天然幂等</b>:scanner 反复扫到同一笔
 * 只会有一条记录。用自增 id 会让同一笔卡单每轮扫描插一条,看板上"卡单数"随时间线性增长,
 * 而真实卡单可能只有一笔。
 *
 * <p>⚠️ <b>{@code bucket_key} 存完整 key(如 {@code slot:101:b:3})而非 bucket_no。</b>
 * 人工处置时直接可用;存 bucket_no 则需要重新拼 key,而拼 key 的规则若与抢号时不一致
 * (比如 {@code bucket_count} 中途被改过),就会回补到**错的桶**上 —— 一个桶多、
 * 一个桶少,Σ 对得上而单桶对不上,是最难查的一类不一致。
 *
 * <p>⚠️ {@code status=0} 待研判是**默认值**,人工介入后才变。{@code idx_status}
 * 就是给"还有多少待研判"这个看板查询用的(07 对账中心第三个 Tab)。
 */
@Data
@TableName("stuck_reservation")
public class StuckReservation {

    /** 天然幂等主键,见类注释。 */
    @TableId(type = IdType.INPUT)
    private Long reservationNo;

    private Long slotId;

    /** 完整桶 key,如 {@code slot:101:b:3}。10.2a 回滚 INCR 用。见类注释。 */
    private String bucketKey;

    /** {@code dup:{slot_date}:{id_card_hash}}。10.2a 回滚 DEL 用。 */
    private String dupKey;

    private Long userId;

    private String idCardHash;

    private LocalDate slotDate;

    /** 达到 {@code reservex.pending.reinject-max} 才入表,记录已补投几次。 */
    private Integer reinjectCount;

    /** 最后一次失败原因,截断到 512 字符。人工研判的起点。 */
    private String lastError;

    /** 0 待研判,1 已重投成功,2 已回滚,3 已忽略。 */
    private Integer status;

    private LocalDateTime createAt;

    private LocalDateTime resolveAt;

    /** 处置人。人工动作必须有责任归属。 */
    private Long resolverId;
}
