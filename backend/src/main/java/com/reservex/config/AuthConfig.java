package com.reservex.config;

import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.entity.User;
import com.reservex.mapper.sharding.UserMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Sa-Token:JWT access 由 Redis 映射支持即时撤销,refresh 由 AuthService 轮换。 */
@Configuration(proxyBeanMethods = false)
public class AuthConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                              jakarta.servlet.http.HttpServletResponse response,
                                              Object handler) {
                        if (!HttpMethod.OPTIONS.matches(request.getMethod())) {
                            StpUtil.checkLogin();
                        }
                        return true;
                    }
                })
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api", "/api/", "/api/sessions", "/api/sessions/current",
                        "/api/users", "/api/users/me", "/api/email-verifications", "/api/captchas",
                        "/api/slots", "/api/slots/**", "/api/registrations/**");
    }

    @Bean
    public StpLogic stpLogic() {
        return new StpLogicJwtForSimple();
    }

    @Bean
    public StpInterface stpInterface(UserMapper userMapper) {
        return new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return List.of();
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                final long userId;
                try {
                    userId = Long.parseLong(loginId.toString());
                } catch (RuntimeException e) {
                    throw BizException.of(ErrorCode.UNAUTHORIZED);
                }
                User user = userMapper.selectById(userId);
                if (user == null) {
                    throw BizException.of(ErrorCode.UNAUTHORIZED);
                }
                if (Integer.valueOf(1).equals(user.getStatus())) {
                    throw BizException.of(ErrorCode.ACCOUNT_BANNED);
                }
                if (Integer.valueOf(1).equals(user.getMustChangePassword())) {
                    throw BizException.of(ErrorCode.UNAUTHORIZED);
                }
                return switch (user.getRole()) {
                    case "ADMIN" -> List.of("ADMIN", "STAFF");
                    case "STAFF" -> List.of("STAFF");
                    case "USER" -> List.of("USER");
                    default -> List.of();
                };
            }
        };
    }
}
