package com.reservex.common;

import lombok.Getter;

/**
 * 业务异常。抛它 = 一个**预期内**的失败,由 {@link GlobalExceptionHandler} 转成
 * 带业务码的 200 响应包。
 *
 * <p>与 {@code RuntimeException} 的分工:非预期异常一律落 {@link ErrorCode#INTERNAL_ERROR}
 * 并打 ERROR 日志;本异常默认**不打栈**(它们是正常业务流,量大且无诊断价值)。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 覆盖默认文案。用于需要带上下文的场景(如"该场次 10:00 开放")。 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }
}
