package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.ErrorCode;
import com.reservex.common.HttpPreconditions;
import com.reservex.common.Result;
import com.reservex.service.AuthService;
import com.reservex.service.RegistrationCodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "reservex_refresh";
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private final AuthService authService;
    private final RegistrationCodeService registrationCodeService;

    @PostMapping("/email-verifications")
    public ResponseEntity<Result<Void>> sendRegistrationCode(@Valid @RequestBody EmailCodeRequest request,
                                                             HttpServletRequest httpRequest) {
        registrationCodeService.send(request.email(), clientIp(httpRequest));
        // send() completes the Redis write and SMTP handoff before returning;
        // 202 would falsely claim asynchronous acceptance.
        return ResponseEntity.ok(Result.ok());
    }

    @PostMapping("/users")
    public ResponseEntity<Result<CreatedUser>> register(@Valid @RequestBody RegisterRequest request,
                                                        HttpServletRequest httpRequest) {
        String registrationKey = registrationKey(httpRequest);
        String clientIp = clientIp(httpRequest);
        AuthService.RegistrationOutcome outcome = authService.registrationByKey(registrationKey,
                request.email(), request.phone(), request.password(), request.idCard(), clientIp);
        if (outcome == null) {
            String fingerprint = authService.registrationRequestFingerprint(request.email(), request.phone(),
                    request.password(), request.idCard());
            boolean newlyVerified = registrationCodeService.consumeForRegistration(
                    request.email(), request.emailCode(), clientIp, registrationKey, fingerprint);
            outcome = authService.registerUserOutcome(request.email(), request.phone(), request.password(),
                    request.idCard(), clientIp, registrationKey, newlyVerified);
        }
        HttpStatus status = outcome.ready() ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        String location = outcome.ready()
                ? "/api/users/" + outcome.userId()
                : "/api/registrations/" + registrationKey;
        return ResponseEntity.status(status)
                .header(HttpHeaders.LOCATION, location)
                .body(Result.ok(new CreatedUser(outcome.userId(), outcome.ready())));
    }

    @GetMapping("/registrations/{registrationKey}")
    public Result<RegistrationStatus> registrationStatus(@PathVariable String registrationKey) {
        String key = validateRegistrationKey(registrationKey);
        String status = authService.registrationStatus(key);
        if (status == null) {
            throw com.reservex.common.BizException.of(ErrorCode.NOT_FOUND);
        }
        return Result.ok(new RegistrationStatus(status));
    }

    @GetMapping("/users/{userId}")
    public Result<AuthService.UserView> user(@PathVariable long userId) {
        StpUtil.checkLogin();
        if (StpUtil.getLoginIdAsLong() != userId) {
            throw com.reservex.common.BizException.of(ErrorCode.NOT_FOUND);
        }
        return Result.ok(authService.getUser(userId));
    }

    @GetMapping("/users/me")
    public Result<AuthService.UserView> currentUser() {
        StpUtil.checkLogin();
        return Result.ok(authService.getUser(StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/sessions")
    public ResponseEntity<Result<?>> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest httpRequest) {
        AuthService.LoginOutcome outcome = authService.login(
                request.email(), request.password(), clientIp(httpRequest));
        if (outcome.onceToken() != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Result.failWithData(ErrorCode.PASSWORD_CHANGE_REQUIRED,
                            new PasswordChangeRequired(outcome.onceToken())));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/sessions/current")
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(outcome.tokens().refreshToken(), httpRequest, REFRESH_TTL).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Result.ok(session(outcome.tokens())));
    }

    @GetMapping("/sessions/current")
    public ResponseEntity<Result<CurrentSession>> currentSession() {
        StpUtil.checkLogin();
        AuthService.UserView user = authService.getUser(StpUtil.getLoginIdAsLong());
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(Result.ok(new CurrentSession(user.userId(), user.role())));
    }

    @PatchMapping("/sessions/current")
    public ResponseEntity<Result<SessionView>> refresh(HttpServletRequest httpRequest) {
        String authorization = httpRequest.getHeader("Authorization");
        String oldAccessToken = authorization != null
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim() : null;
        AuthService.TokenPair tokens = authService.refresh(
                refreshToken(httpRequest), oldAccessToken, clientIp(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(tokens.refreshToken(), httpRequest, REFRESH_TTL).toString())
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(Result.ok(session(tokens)));
    }

    @DeleteMapping("/sessions/current")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        Long accessUserId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        authService.logout(accessUserId, refreshToken(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie("", request, Duration.ZERO).toString())
                .cacheControl(org.springframework.http.CacheControl.noStore()).build();
    }

    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        return forwardedFor == null || forwardedFor.isBlank()
                ? request.getRemoteAddr() : forwardedFor.trim();
    }

    private static String registrationKey(HttpServletRequest request) {
        String value = request.getHeader("Idempotency-Key");
        if (value == null || value.isBlank()) {
            throw new com.reservex.common.BizException(
                    ErrorCode.BAD_REQUEST, "Idempotency-Key 请求头必填");
        }
        return HttpPreconditions.requireIdempotencyKey(value);
    }

    private static String validateRegistrationKey(String value) {
        return HttpPreconditions.requireIdempotencyKey(value);
    }

    @PatchMapping("/users/me")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordRequest request,
                                               HttpServletRequest httpRequest) {
        Long accessUserId = null;
        if (request.onceToken() == null || request.onceToken().isBlank()) {
            StpUtil.checkLogin();
            accessUserId = StpUtil.getLoginIdAsLong();
        }
        authService.changePassword(accessUserId, request.onceToken(),
                request.oldPassword(), request.newPassword());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie("", httpRequest, Duration.ZERO).toString())
                .build();
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 128) String email,
            @NotBlank @Pattern(regexp = "^\\d{6}$") String emailCode,
            @NotBlank @Pattern(regexp = "^1\\d{10}$") String phone,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Pattern(regexp = "^[1-9]\\d{16}[0-9Xx]$") String idCard) {
    }

    public record EmailCodeRequest(@NotBlank @Email @Size(max = 128) String email) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 128) String email,
            @NotBlank @Size(max = 72) String password) {
    }

    public record PasswordRequest(
            @NotBlank @Size(max = 72) String oldPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword,
            @Size(max = 32) String onceToken) {
    }

    public record PasswordChangeRequired(String onceToken) {
    }

    public record CreatedUser(Long userId, boolean ready) {
        public CreatedUser(Long userId) {
            this(userId, true);
        }
    }

    public record RegistrationStatus(String status) {
    }

    public record SessionView(String accessToken, Long userId, String role) {
    }

    public record CurrentSession(Long userId, String role) {
    }

    private static SessionView session(AuthService.TokenPair tokens) {
        return new SessionView(tokens.accessToken(), tokens.userId(), tokens.role());
    }

    private static String refreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (REFRESH_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static ResponseCookie refreshCookie(String value, HttpServletRequest request,
                                                Duration maxAge) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        boolean secure = request.isSecure() || "https".equalsIgnoreCase(forwardedProto);
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/sessions")
                .maxAge(maxAge)
                .build();
    }
}
