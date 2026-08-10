package com.reservex.common;

import lombok.Data;
import org.slf4j.MDC;

/**
 * 统一响应包(07 §3·补)。
 *
 * <p>约定:**HTTP 状态码恒为 200,业务结果看 {@code code}**(鉴权 401/403 除外)。
 * 理由:前端只需一处解包逻辑;若业务失败用 4xx/5xx,浏览器控制台会满屏红,
 * 而"库存不足"并不是错误。
 *
 * <p>{@code requestId} 从 MDC 取,与日志里的 {@code %X{requestId}} 是同一个值 ——
 * 用户截图报错时,这一个串就能把整条链路的日志捞出来(08 §6.0)。
 *
 * <p>⚠️ 序列化时 Long 一律转字符串,由 {@code common/JacksonConfig} 统一配置。
 * 不要逐 DTO 标 {@code @JsonSerialize} —— 漏一个就是一条静默错路径(07 §3·补·4)。
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private String requestId;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.requestId = MDC.get(RequestIdFilter.MDC_KEY);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMessage(), data);
    }

    public static Result<Void> ok() {
        return new Result<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    public boolean isOk() {
        return code == ErrorCode.OK.getCode();
    }
}
