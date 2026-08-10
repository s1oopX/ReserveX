package com.reservex.config;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Sa-Token:access JWT 无状态,refresh 由 AuthService 单独存 Redis。 */
@Configuration(proxyBeanMethods = false)
public class AuthConfig {

    @Bean
    public StpLogic stpLogic() {
        return new StpLogicJwtForStateless();
    }

    @Bean
    public StpInterface stpInterface(StpLogic stpLogic) {
        return new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return List.of();
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                Object role = stpLogic.getExtra("role");
                if (role == null) {
                    return List.of();
                }
                return switch (role.toString()) {
                    case "ADMIN" -> List.of("ADMIN", "STAFF");
                    case "STAFF" -> List.of("STAFF");
                    case "USER" -> List.of("USER");
                    default -> List.of();
                };
            }
        };
    }
}
