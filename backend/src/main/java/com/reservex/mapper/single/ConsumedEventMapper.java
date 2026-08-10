package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.ConsumedEvent;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 消费幂等 Mapper(**单库**)。
 *
 * <p>⚠️ <b>复合主键 {@code (consumer_group, event_id)} → 不能用 {@code selectById}</b>
 * (见 {@link ConsumedEvent} 类注释)。
 *
 * <p><b>写入时机有两种,判据在 06 §4.1</b>:
 * <ul>
 *   <li>重复执行一次有唯一键/CAS 能变 no-op(如落库消费者)→ <b>成功后写</b>,
 *       用 {@link #markConsumed};</li>
 *   <li>没有(界外调用,如发邮件)→ <b>动作前写</b>,用 {@link #tryMarkBefore} 抢占,
 *       抢不到说明别人正在发/已发过,直接跳过。</li>
 * </ul>
 * 用错时机的后果不对称:前者用"动作前写"会在失败重试时被自己挡住(消息永远消费不掉);
 * 后者用"成功后写"会在发信成功但写库失败时重复发信。
 */
public interface ConsumedEventMapper extends BaseMapper<ConsumedEvent> {

    /**
     * 成功后写。撞主键说明本条已处理过,幂等跳过。
     *
     * @return 0 表示已消费过
     */
    int markConsumed(@Param("consumerGroup") String consumerGroup,
                     @Param("eventId") String eventId,
                     @Param("consumedAt") LocalDateTime consumedAt);

    /**
     * 动作前抢占(界外调用专用)。语义与 {@link #markConsumed} 相同,
     * 分成两个方法是为了让调用点自己声明用的是哪种时机 —— 同名调用看不出区别,
     * 而这恰恰是最容易用错的地方。
     *
     * @return 0 表示没抢到(别人正在处理或已处理),应直接返回成功不再执行动作
     */
    int tryMarkBefore(@Param("consumerGroup") String consumerGroup,
                      @Param("eventId") String eventId,
                      @Param("consumedAt") LocalDateTime consumedAt);

    /** 幂等查询。极少用 —— 正常路径靠写入撞主键判断,而非先查后写(并发下不可靠)。 */
    int existsBy(@Param("consumerGroup") String consumerGroup,
                 @Param("eventId") String eventId);
}
