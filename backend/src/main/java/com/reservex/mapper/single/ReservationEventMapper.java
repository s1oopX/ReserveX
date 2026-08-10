package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.ReservationEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 预约事件流水 Mapper(**单库**)。只插不改不删。
 *
 * <p>继承 {@code BaseMapper} 但实际只用 {@code insert}:
 * {@code updateById} / {@code deleteById} 在语义上是被禁止的(不可变日志),
 * 只是没有在接口层硬拦(拦了也行,看团队纪律)。
 *
 * <p>⚠️ <b>与预约落库同一个事务</b>(都在 single 库,都用 singleTxManager)。
 * 分开提交会出现"有预约无事件"的空档,排查时看不到 CREATED 事件。
 */
@Mapper
public interface ReservationEventMapper extends BaseMapper<ReservationEvent> {

    /**
     * 记一次状态迁移。
     *
     * <p>⚠️ <b>IGNORE 而非 insert</b>:{@code event_id} 由业务确定性派生
     * (如 {@code 'rc-'+rno}),消息重投时会算出同一个 id。用 {@code insert} 会抛
     * {@code DuplicateKeyException} 把整个消费事务带崩,而这本该是一次成功的幂等跳过。
     */
    int insertIgnore(ReservationEvent event);

    /** 预约详情页的时间线(正序:先发生的在上)。 */
    List<ReservationEvent> selectByReservation(@Param("reservationNo") Long reservationNo);
}
