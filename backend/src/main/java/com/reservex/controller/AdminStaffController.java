package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.ErrorCode;
import com.reservex.common.HttpPreconditions;
import com.reservex.common.Result;
import com.reservex.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员对 STAFF 账号的管理(08 §4.1)。
 *
 * <p>⚠️ role 硬编码 STAFF,不暴露给 HTTP 请求体 —— 任何能设 role 的 HTTP 端点即提权漏洞
 * (User 红线:ADMIN 只能 seed/bootstrap 产生)。
 */
@RestController
@RequestMapping("/api/staff-members")
@RequiredArgsConstructor
public class AdminStaffController {

    private final AuthService authService;

    @GetMapping
    public Result<List<AuthService.StaffView>> list() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(authService.listStaff());
    }

    @GetMapping("/{userId}")
    public ResponseEntity<Result<AuthService.StaffView>> detail(@PathVariable long userId) {
        StpUtil.checkRole("ADMIN");
        AuthService.StaffView view = authService.getStaff(userId);
        return ResponseEntity.ok()
                .eTag(HttpPreconditions.etag(view.version()))
                .body(Result.ok(view));
    }

    @PostMapping
    public ResponseEntity<Result<CreatedStaff>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateStaffRequest request) {
        StpUtil.checkRole("ADMIN");
        long operatorId = StpUtil.getLoginIdAsLong();
        AuthService.RegistrationOutcome outcome = authService.createStaff(request.email(), request.phone(),
                request.password(), request.idCard(), operatorId,
                HttpPreconditions.requireIdempotencyKey(idempotencyKey));
        HttpStatus status = outcome.ready() ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        String location = outcome.ready()
                ? "/api/staff-members/" + outcome.userId()
                : "/api/admin/registration-jobs/" + outcome.userId();
        return ResponseEntity.status(status)
                .header(HttpHeaders.LOCATION, location)
                .body(Result.ok(new CreatedStaff(outcome.userId(), outcome.ready())));
    }

    @PatchMapping("/{userId}")
    public ResponseEntity<Result<AuthService.StaffView>> setStatus(
            @PathVariable long userId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody StaffStatusRequest request) {
        StpUtil.checkRole("ADMIN");
        AuthService.StaffView updated = authService.setStaffBanned(userId, request.banned(),
                StpUtil.getLoginIdAsLong(), HttpPreconditions.requireVersion(ifMatch));
        return ResponseEntity.ok()
                .eTag(HttpPreconditions.etag(updated.version()))
                .body(Result.ok(updated));
    }

    public record CreateStaffRequest(
            @NotBlank @Email @Size(max = 128) String email,
            @NotBlank @Pattern(regexp = "^1\\d{10}$") String phone,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Pattern(regexp = "^[1-9]\\d{16}[0-9Xx]$") String idCard) {
    }

    public record StaffStatusRequest(@NotNull Boolean banned) {
    }

    public record CreatedStaff(Long userId, boolean ready) {
    }
}
