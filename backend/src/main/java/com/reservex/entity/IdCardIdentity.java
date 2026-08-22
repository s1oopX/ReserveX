package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 单库身份证账号归属；主键在数据库层保证一个身份证只能注册一个账号。 */
@Data
@TableName("id_card_identity")
public class IdCardIdentity {

    @TableId(type = IdType.INPUT)
    private String idCardHash;

    private Long userId;

    private LocalDateTime createAt;
}
