package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.ErrorCode;
import com.reservex.common.Result;
import com.reservex.entity.AuditLog;
import com.reservex.entity.User;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.common.TimeSupport;
import com.reservex.service.AuthService;
import com.reservex.mapper.sharding.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员对 STAFF 账号的管理(08 §4.1)。
 *
 * <p>⚠️ role 硬编码 STAFF,不暴露给 HTTP 请求体 —— 任何能设 role 的 HTTP 端点即提权漏洞
 * (User 红线:ADMIN 只能 seed/bootstrap 产生)。
 */
@RestController
@RequestMapping("/api/admin/staff")
@RequiredArgsConstructor
public class AdminStaffController {

    private final AuthService authService;
    private final UserMapper userMapper;
    private final AuditLogMapper auditMapper;
    private final IdGenerator idGenerator;
    private final TimeSupport time;

    @GetMapping
    public Result<List<AuthService.StaffView>> list() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(authService.listStaff());
    }

    @PostMapping
    public Result<Void> create(@Valid @RequestBody CreateStaffRequest request) {
        StpUtil.checkRole("ADMIN");
        long operatorId = StpUtil.getLoginIdAsLong();
        authService.createStaff(request.email(), request.phone(),
                request.password(), request.idCard(), operatorId);
        recordAudit(operatorId);
        return Result.ok(null);
    }

    private void recordAudit(long operatorId) {
        AuditLog audit = new AuditLog();
        audit.setId(idGenerator.nextId());
        audit.setOperatorType("ADMIN");
        audit.setOperatorId(operatorId);
        audit.setAction("CREATE_STAFF");
        audit.setTargetType("user");
        audit.setBefore(null);
        audit.setAfter("{\"role\":\"STAFF\"}");
        audit.setRequestId("admin-staff-" + operatorId);
        audit.setCreateAt(time.now());
        auditMapper.insert(audit);
    }

    public record CreateStaffRequest(
            @NotBlank @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") String email,
            @NotBlank @Pattern(regexp = "^1\\d{10}$") String phone,
            @NotBlank String password,
            @NotBlank @Pattern(regexp = "^[1-9]\\d{16}[0-9Xx]$") String idCard) {
    }
}
