package com.reservex.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 与 docs/07 和前端 codes.ts 共用的字符串错误码。 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    OK("成功"),
    BAD_REQUEST("请求参数不合法"),
    NOT_FOUND("资源不存在"),
    UNAUTHORIZED("未登录或登录已过期"),
    FORBIDDEN("无权限"),
    INTERNAL_ERROR("系统异常,请稍后重试"),

    SLOT_NOT_FOUND("场次不存在"),
    SLOT_NOT_RELEASED("尚未放号"),
    SLOT_ENDED("预约已结束"),
    SLOT_FULL("名额已满"),
    SLOT_RELEASED_LOCKED("该场次已放号,此项不可改"),
    TEMPLATE_INVALID("放号时点晚于场次结束,或容量小于分桶数"),

    QUOTA_USED("您今天已有预约"),
    CAPTCHA_REQUIRED("请先完成图形验证"),
    CAPTCHA_INVALID("验证码错误,请重试"),
    RATE_LIMITED("预约人数较多,请稍后重试"),
    SERVICE_DEGRADED("系统繁忙,请稍后重试"),

    RESERVATION_NOT_FOUND("预约不存在"),
    RESERVATION_CONFIRMING("预约正在确认,请稍候重试"),
    ALREADY_CANCELLED("已取消,无法操作"),
    ALREADY_EXPIRED("已过期,无法操作"),
    ALREADY_VERIFIED("该预约已核销"),
    QR_INVALID("无效二维码"),
    QR_EXPIRED("二维码已过期,请刷新"),

    EMAIL_TAKEN("邮箱已注册"),
    PHONE_TAKEN("手机号已注册"),
    LOGIN_FAILED("邮箱或密码错误"),
    ACCOUNT_BANNED("账号已封禁,请联系管理员"),
    PASSWORD_CHANGE_REQUIRED("首次登录请先修改密码");

    private final String message;

    public String getCode() {
        return name();
    }
}
