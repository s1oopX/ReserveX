package com.reservex.mapper.sharding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.ReservationTransitionOutbox;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ReservationTransitionOutboxMapper extends BaseMapper<ReservationTransitionOutbox> {

    List<ReservationTransitionOutbox> selectPending(@Param("limit") Integer limit);

    int deletePending(@Param("transitionId") String transitionId,
                      @Param("userId") Long userId);
}
