package com.reservex.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 全局异常 → 响应包(07 §3·补)。
 *
 * <p><b>核心纪律:不把系统故障说成用户错误。</b>Redis 不可用时返
 * {@link ErrorCode#SERVICE_DEGRADED},绝不复用业务失败码 —— 比如内存打满导致
 * 写 refresh 失败时若返 {@code LOGIN_FAILED},用户会以为自己密码错了。
 * 这是撒谎,也是红线 #32/#40 收的同一类问题(08 §4.6)。
 *
 * <p><b>兜底 handler 必须存在</b>:没有它,未捕获异常会走 Spring 默认的
 * {@code /error} 白页,返回体结构与 {@link Result} 完全不同 → 前端解包炸在
 * "读不到 code 字段"上,而真正的错因被埋掉。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> onBiz(BizException e) {
        // 预期内失败,不打栈:它们量大(库存不足/限流)且无诊断价值
        log.debug("业务异常 code={} msg={}", e.getErrorCode().getCode(), e.getMessage());
        HttpStatus status = switch (e.getErrorCode()) {
            case BAD_REQUEST, CAPTCHA_INVALID, REGISTRATION_CODE_INVALID -> HttpStatus.BAD_REQUEST;
            case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case PRECONDITION_FAILED -> HttpStatus.PRECONDITION_FAILED;
            case STATE_CONFLICT, PASSWORD_CHANGE_REQUIRED, SLOT_NOT_RELEASED, SLOT_ENDED,
                    SLOT_RELEASED_LOCKED, RESERVATION_NOT_STARTED -> HttpStatus.CONFLICT;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND, SLOT_NOT_FOUND, RESERVATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case SERVICE_DEGRADED -> HttpStatus.SERVICE_UNAVAILABLE;
            case LOGIN_FAILED -> HttpStatus.UNAUTHORIZED;
            case ACCOUNT_BANNED -> HttpStatus.FORBIDDEN;
            case REGISTRATION_CONFLICT, SLOT_FULL, QUOTA_USED,
                    RESERVATION_CONFIRMING, REFRESH_IN_PROGRESS,
                    ALREADY_CANCELLED, ALREADY_EXPIRED,
                    ALREADY_VERIFIED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        var response = ResponseEntity.status(status);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(Result.fail(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> onInvalidRequest(Exception e) {
        log.debug("参数校验失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.BAD_REQUEST));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Void>> onRequestShape(Exception e) {
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.BAD_REQUEST));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> onMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        var response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (e.getSupportedHttpMethods() != null) {
            Set<HttpMethod> allowed = new LinkedHashSet<>(e.getSupportedHttpMethods());
            if (allowed.contains(HttpMethod.GET)) {
                allowed.add(HttpMethod.HEAD);
            }
            allowed.add(HttpMethod.OPTIONS);
            response.allow(allowed.toArray(HttpMethod[]::new));
            if (allowed.contains(HttpMethod.PATCH)) {
                response.header(HttpHeaders.ACCEPT_PATCH, "application/json");
            }
        }
        return response.body(Result.fail(ErrorCode.BAD_REQUEST, "不支持的 HTTP 方法"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> onMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.fail(ErrorCode.BAD_REQUEST, "不支持的 Content-Type"));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> onNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(Result.fail(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<Result<Void>> onNotRole(NotRoleException e) {
        log.warn("越权访问:需要角色 {}", e.getRole());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail(ErrorCode.FORBIDDEN));
    }

    /**
     * 唯一键冲突。**不当成 500**:它在本项目里是有业务含义的正常竞态 ——
     * 注册撞 {@code email_route} PK、抢号撞 {@code id_card_route} PK、
     * 对账任务重跑撞 {@code uk_task_period_slot},都靠它收敛。
     *
     * <p>⚠️ 具体该返哪个码取决于撞的是哪个键,由调用方 catch 后转成语义明确的
     * {@link BizException};走到这里说明有一处漏了转换,故打 WARN 留痕。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> onDuplicateKey(DuplicateKeyException e) {
        // MySQL 的重复键文本可能包含邮箱、手机号或证件 hash，禁止原样进日志。
        log.warn("未被业务层转换的唯一键冲突,请补 catch: type={}",
                e.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.fail(ErrorCode.STATE_CONFLICT));
    }

    /** 数据存储连接、命令或查询超时都归降级,用户可稍后重试。 */
    @ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class,
            QueryTimeoutException.class})
    public ResponseEntity<Result<Void>> onDataStoreDown(RuntimeException e) {
        log.error("数据存储不可用,链路降级: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail(ErrorCode.SERVICE_DEGRADED));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Result<Void>> onNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> onUnexpected(Exception e) {
        log.error("未预期异常", e);
        // 不把 e.getMessage() 返给前端:堆栈/SQL 片段可能带表名、列名甚至参数值
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }
}
