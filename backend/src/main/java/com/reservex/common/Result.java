package com.reservex.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.slf4j.MDC;

/**
 * 统一响应包(07 §3·补)。
 *
 * <p>业务失败按语义返回 4xx/5xx，同时保持统一响应体，调用方仍可稳定读取 {@code code}。
 *
 * <p>{@code requestId} 从 MDC 取,与日志里的 {@code %X{requestId}} 是同一个值 ——
 * 用户截图报错时,这一个串就能把整条链路的日志捞出来(08 §6.0)。
 *
 * <p>⚠️ 序列化时 Long 一律转字符串,由 {@code common/JacksonConfig} 统一配置。
 * 不要逐 DTO 标 {@code @JsonSerialize} —— 漏一个就是一条静默错路径(07 §3·补·4)。
 */
@Data
public class Result<T> {

    private String code;
    private String msg;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private T data;
    private String requestId;

    private Result(String code, String msg, T data) {
        this.code = code;
        this.msg = msg;
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

    public static <T> Result<T> failWithData(ErrorCode errorCode, T data) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String msg) {
        return new Result<>(errorCode.getCode(), msg, null);
    }
}
