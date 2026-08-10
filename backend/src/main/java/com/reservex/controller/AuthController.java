package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.ErrorCode;
import com.reservex.common.Result;
import com.reservex.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.email(), request.phone(), request.password(), request.idCard());
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginOutcome outcome = authService.login(request.email(), request.password());
        if (outcome.onceToken() != null) {
            return Result.failWithData(ErrorCode.PASSWORD_CHANGE_REQUIRED,
                    new PasswordChangeRequired(outcome.onceToken()));
        }
        return Result.ok(outcome.tokens());
    }

    @PostMapping("/refresh")
    public Result<AuthService.TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return Result.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody(required = false) RefreshRequest request) {
        StpUtil.checkLogin();
        authService.logout(StpUtil.getLoginIdAsLong(), request == null ? null : request.refreshToken());
        return Result.ok();
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody PasswordRequest request) {
        Long accessUserId = null;
        if (request.onceToken() == null || request.onceToken().isBlank()) {
            StpUtil.checkLogin();
            accessUserId = StpUtil.getLoginIdAsLong();
        }
        authService.changePassword(accessUserId, request.onceToken(),
                request.oldPassword(), request.newPassword());
        return Result.ok();
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 128) String email,
            @NotBlank @Pattern(regexp = "^1\\d{10}$") String phone,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Pattern(regexp = "^[1-9]\\d{16}[0-9Xx]$") String idCard) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 128) String email,
            @NotBlank @Size(max = 72) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 96) String refreshToken) {
    }

    public record PasswordRequest(
            @NotBlank @Size(max = 72) String oldPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword,
            @Size(max = 32) String onceToken) {
    }

    public record PasswordChangeRequired(String onceToken) {
    }
}
