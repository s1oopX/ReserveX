package com.reservex.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
    public Result<Void> onBiz(BizException e) {
        // 预期内失败,不打栈:它们量大(库存不足/限流)且无诊断价值
        log.debug("业务异常 code={} msg={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            HttpMessageNotReadableException.class})
    public Result<Void> onInvalidRequest(Exception e) {
        log.debug("参数校验失败: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(NotLoginException.class)
    public Result<Void> onNotLogin(NotLoginException e) {
        return Result.fail(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(NotRoleException.class)
    public Result<Void> onNotRole(NotRoleException e) {
        log.warn("越权访问:需要角色 {}", e.getRole());
        return Result.fail(ErrorCode.FORBIDDEN);
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
    public Result<Void> onDuplicateKey(DuplicateKeyException e) {
        log.warn("未被业务层转换的唯一键冲突,请补 catch: {}", e.getMostSpecificCause().getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST);
    }

    /**
     * Redis 故障三形态(06 §6.1):连不上、命令报错、内存打满(写命令返 OOM)。
     * 三者都归降级,而不是 500 —— 因为对用户的正确指引是"稍后重试",不是"系统坏了"。
     */
    @ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class})
    public Result<Void> onRedisDown(RuntimeException e) {
        log.error("Redis 不可用,链路降级: {}", e.getMessage());
        return Result.fail(ErrorCode.SERVICE_DEGRADED);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> onUnexpected(Exception e) {
        log.error("未预期异常", e);
        // 不把 e.getMessage() 返给前端:堆栈/SQL 片段可能带表名、列名甚至参数值
        return Result.fail(ErrorCode.INTERNAL_ERROR);
    }
}
