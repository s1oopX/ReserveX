package com.reservex.common;

import lombok.Getter;

/**
 * 全局错误码(07 §3·补)。
 *
 * <p><b>纪律:错误码是对外契约,只增不改。</b>前端按 {@code code} 分支,
 * 改一个已发布的码值等于让存量前端走错分支 —— 而且不报错。
 *
 * <p><b>纪律:不许把系统故障说成用户错误。</b>Redis 内存打满时登录失败必须返
 * {@link #SERVICE_DEGRADED} 而不是 {@link #LOGIN_FAILED} —— 后者会让用户以为自己密码错了,
 * 这是撒谎(08 §4.6 / 红线 #32、#40)。同理,库存不足返 {@link #SLOT_SOLD_OUT},
 * 限流返 {@link #RATE_LIMITED},两者语义完全不同,不能混。
 */
@Getter
public enum ErrorCode {

    // ---- 通用 ----------------------------------------------------------
    OK(0, "成功"),
    BAD_REQUEST(400, "请求参数不合法"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "系统繁忙,请稍后重试"),

    // ---- 登录注册(07 §3·补 四码)-------------------------------------
    /** ⚠️ 邮箱不存在与密码错误**共用此码**:区分开就是给攻击者做用户枚举(03 §2.2)。 */
    LOGIN_FAILED(1001, "邮箱或密码错误"),
    ACCOUNT_BANNED(1002, "账号已被封禁"),
    CAPTCHA_REQUIRED(1003, "请先完成图形验证"),
    CAPTCHA_INVALID(1004, "验证码错误或已过期"),
    EMAIL_ALREADY_EXISTS(1005, "该邮箱已注册"),
    PHONE_ALREADY_EXISTS(1006, "该手机号已注册"),
    PASSWORD_CHANGE_REQUIRED(1007, "首次登录请先修改密码"),

    // ---- 抢号(02)----------------------------------------------------
    SLOT_NOT_FOUND(2001, "场次不存在"),
    SLOT_NOT_RELEASED(2002, "该场次尚未开放预约"),
    SLOT_EXPIRED(2003, "该场次已结束"),
    SLOT_SOLD_OUT(2004, "该场次已约满"),
    /** 一人一证一天一约(M6):Lua 的 dup 与 route PK 双防线都会走到这个码。 */
    DAILY_QUOTA_EXCEEDED(2005, "同一证件每天只能预约一次"),
    RATE_LIMITED(2006, "操作过于频繁,请稍后重试"),
    /** 落库尚未完成的窗口期。前端应轮询而非当作失败(02 §三)。 */
    RESERVATION_PENDING(2007, "预约处理中,请稍候"),

    // ---- 预约生命周期(01)---------------------------------------------
    RESERVATION_NOT_FOUND(3001, "预约不存在"),
    RESERVATION_NOT_CANCELLABLE(3002, "当前状态不可取消"),
    RESERVATION_ALREADY_VERIFIED(3003, "该预约已核销"),
    RESERVATION_CANCELLED(3004, "该预约已取消"),
    RESERVATION_EXPIRED(3005, "该预约已过期"),

    // ---- 核销(07 §3.4.1)---------------------------------------------
    QR_INVALID(4001, "二维码无效"),
    QR_EXPIRED(4002, "二维码已过期,请刷新"),
    QR_SIGNATURE_MISMATCH(4003, "二维码验签失败"),
    VERIFY_DUPLICATED(4004, "该预约已被核销"),

    // ---- 管理端(07 §四)---------------------------------------------
    TEMPLATE_NOT_FOUND(5001, "场次模板不存在"),
    TEMPLATE_HOUR_CONFLICT(5002, "该时段已存在模板"),
    /** 已放号的 slot 禁改 bucket_count:改了 hash 路由错位(03 §九)。 */
    SLOT_RELEASED_IMMUTABLE(5003, "场次已放号,该字段不可修改"),
    CAPACITY_ONLY_INCREASE(5004, "容量只能增加,不能减少"),

    // ---- 降级(05 §五)-----------------------------------------------
    /** Redis 不可用/内存打满。**不要用业务失败码冒充它。** */
    SERVICE_DEGRADED(9001, "服务暂时降级,请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
