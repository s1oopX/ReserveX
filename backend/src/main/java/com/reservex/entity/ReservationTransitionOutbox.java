package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable handoff from the sharded reservation state to single-store audit tables. */
@Data
@TableName("reservation_transition_outbox")
public class ReservationTransitionOutbox {

    @TableId(type = IdType.INPUT)
    private String transitionId;

    /** Sharding key; this row must live beside its reservation. */
    private Long userId;
    private Long reservationNo;
    private String eventType;
    private String operatorType;
    private Long operatorId;
    private Integer method;
    private String qrNonce;
    private Boolean manual;
    private Long verificationId;
    private Long auditId;
    private String requestId;
    private LocalDateTime eventTime;
    private LocalDateTime createAt;
}
