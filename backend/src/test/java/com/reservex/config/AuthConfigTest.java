package com.reservex.config;

import cn.dev33.satoken.stp.StpInterface;
import com.reservex.common.BizException;
import com.reservex.entity.User;
import com.reservex.mapper.sharding.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthConfigTest {

    @Test
    void onceTokenPasswordChangeCanReachItsControllerWithoutAnAccessSession() {
        RecordingRegistry registry = new RecordingRegistry();

        new AuthConfig().addInterceptors(registry);

        assertEquals(true, registry.usersMeExcluded);
    }

    @Test
    void optionsCanDiscoverProtectedResourcesWithoutAnAccessSession() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        new AuthConfig().addInterceptors(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/reservations/1");

        assertTrue(registry.interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void rolesAndBanStateComeFromTheLiveUserRow() {
        UserMapper users = mock(UserMapper.class);
        User staff = new User();
        staff.setRole("STAFF");
        staff.setStatus(0);
        when(users.selectById(42L)).thenReturn(staff);

        StpInterface auth = new AuthConfig().stpInterface(users);
        assertEquals(List.of("STAFF"), auth.getRoleList("42", "login"));

        staff.setStatus(1);
        assertThrows(BizException.class, () -> auth.getRoleList("42", "login"));

        staff.setStatus(0);
        staff.setMustChangePassword(1);
        assertThrows(BizException.class, () -> auth.getRoleList("42", "login"));
    }

    private static final class RecordingRegistry extends InterceptorRegistry {
        private boolean usersMeExcluded;
        private HandlerInterceptor interceptor;

        @Override
        public org.springframework.web.servlet.config.annotation.InterceptorRegistration addInterceptor(
                org.springframework.web.servlet.HandlerInterceptor interceptor) {
            this.interceptor = interceptor;
            return new org.springframework.web.servlet.config.annotation.InterceptorRegistration(interceptor) {
                @Override
                public org.springframework.web.servlet.config.annotation.InterceptorRegistration excludePathPatterns(
                        String... patterns) {
                    usersMeExcluded = java.util.Arrays.asList(patterns).contains("/api/users/me");
                    return super.excludePathPatterns(patterns);
                }
            };
        }
    }
}
