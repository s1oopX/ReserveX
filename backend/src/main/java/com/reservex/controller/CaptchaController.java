package com.reservex.controller;

import com.reservex.common.Result;
import com.reservex.service.CaptchaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 图形验证码(D4)。
 *
 * <p>{@code POST /api/captchas} 生成(无需登录,登录前也可被风控场景复用);
 * 校验不单独开端点 —— 由 {@code POST /api/reservations} 原子校验。
 */
@RestController
@RequestMapping("/api/captchas")
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;

    @PostMapping
    public ResponseEntity<Result<CaptchaService.CaptchaView>> generate(HttpServletRequest request) {
        CaptchaService.CaptchaView captcha = captchaService.generate(clientIp(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Result.ok(captcha));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        return forwardedFor == null || forwardedFor.isBlank()
                ? request.getRemoteAddr() : forwardedFor.trim();
    }

}
