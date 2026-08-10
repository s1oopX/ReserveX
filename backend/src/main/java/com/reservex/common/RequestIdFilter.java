package com.reservex.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * requestId 贯穿:HTTP → 日志 → 消息 → 消费者(08 §6.0 Tracing 一件)。
 *
 * <p>v1 用 MDC 而不引 SkyWalking:目标是"5 分钟内定位为什么这个 rno 状态错了",
 * 一个能把 controller 日志、Lua 返回值、消费者五阶段日志串起来的 id 就够了。
 *
 * <p>⚠️ 消息体里必须带上这个 id({@code reservation_event.request_id} 是 NOT NULL 列)。
 * 消费者线程与请求线程不是同一根,MDC 不会自动传过去 —— 消费者要从消息头显式取出并
 * {@code MDC.put}。这正是 README 纪律 #4 的实例:每个 NOT NULL 列都要问"所有写入路径拿什么填它"。
 */
@Component
@Order(Integer.MIN_VALUE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat 线程会被复用:不清就把上一次请求的 id 带给下一个请求,
            // 日志看起来完全正常却指向错的链路 —— 比没有 id 更坏
            MDC.remove(MDC_KEY);
        }
    }
}
