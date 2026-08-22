package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable payload for the single-store route -> sharded user registration. */
@Data
@TableName("registration_outbox")
public class RegistrationOutbox {

    @TableId(type = IdType.INPUT)
    private Long userId;
    private String registrationKey;
    private String requestFingerprint;
    private String email;
    private String phone;
    private String password;
    private byte[] idCardCiphertext;
    private String idCardKeyId;
    private String idCardHash;
    private String idCardMasked;
    private String role;
    private Integer userStatus;
    private Integer userVersion;
    private Integer userMustChangePassword;
    private Integer status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime leaseUntil;
    private String leaseOwner;
    private String lastError;
    private Long operatorId;
    private Long auditId;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;
}
