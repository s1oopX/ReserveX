package com.reservex.controller;

import com.reservex.common.Result;
import com.reservex.service.CaptchaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图形验证码(D4)。
 *
 * <p>{@code GET /api/captcha} 生成(无需登录,登录前也可被风控场景复用);
 * 校验不单独开端点 —— 由 {@code /reservation/grab} 在风控触发时带 captchaToken 一并校验。
 */
@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;

    @GetMapping
    public Result<CaptchaService.CaptchaView> generate() {
        return Result.ok(captchaService.generate());
    }

    /**
     * 校验端点(可选):前端可在提交抢号前预校验,但抢号端点本身也会校验。
     * 此端点主要用于联调与"用户想知道码对不对"。
     */
    @PostMapping("/verify")
    public Result<Boolean> verify(@RequestBody VerifyRequest request) {
        return Result.ok(captchaService.verify(request.key(), request.input()));
    }

    public record VerifyRequest(String key, String input) {
    }
}
