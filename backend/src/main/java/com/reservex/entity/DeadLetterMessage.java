package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** RocketMQ 重试耗尽后的单库落点。 */
@Data
@TableName("dead_letter_message")
public class DeadLetterMessage {

    @TableId(type = IdType.INPUT)
    private String messageId;

    private String sourceGroup;
    private String targetTopic;
    private String body;
    private Integer reconsumeTimes;
    /** 0 待处理，1 已重放，2 重放处理中。 */
    private Integer status;
    private LocalDateTime capturedAt;
    private LocalDateTime updateAt;
    private Long resolverId;
}
