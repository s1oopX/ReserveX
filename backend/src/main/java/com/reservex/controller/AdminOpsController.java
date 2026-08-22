package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.Result;
import com.reservex.entity.ReconcileLog;
import com.reservex.entity.StuckReservation;
import com.reservex.service.ReconcileService;
import com.reservex.service.AdminReservationQueryService;
import com.reservex.service.RegistrationOutboxService;
import com.reservex.service.SlotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOpsController {

    private final ReconcileService reconcileService;
    private final SlotService slotService;
    private final AdminReservationQueryService reservationQuery;
    private final RegistrationOutboxService registrationOutboxes;

    @GetMapping("/reconciliation-logs")
    public Result<List<ReconciliationLogView>> reconciliationLogs(
            @RequestParam(defaultValue = "current") String scope) {
        StpUtil.checkRole("ADMIN");
        List<ReconcileLog> rows = switch (scope) {
            case "current" -> reconcileService.diffs();
            case "latest" -> reconcileService.latest();
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的对账日志范围");
        };
        return Result.ok(rows.stream().map(AdminOpsController::toView).toList());
    }

    @GetMapping("/stuck-reservations")
    public Result<List<StuckView>> stuck() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(reconcileService.stuck().stream().map(AdminOpsController::toView).toList());
    }

    @GetMapping("/dashboard")
    public Result<ReconcileService.Dashboard> dashboard() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(reconcileService.dashboard());
    }

    /**
     * 管理员全园预约查询(广播两库归并 + 脱敏)。
     * 参数皆可选:不传则返最近 500 条(按 create_at desc),并明确是否仍有更多。
     */
    @GetMapping("/reservations")
    public Result<AdminReservationQueryService.ReservationPage> reservations(
            @RequestParam(required = false) Long rno,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate slotDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int size) {
        StpUtil.checkRole("ADMIN");
        return Result.ok(reservationQuery.list(rno, slotDate, status, cursor, size));
    }

    @GetMapping("/release-monitor")
    public Result<List<SlotService.ReleaseMonitorView>> releaseMonitor() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(slotService.listReleaseMonitor());
    }

    @GetMapping("/registration-jobs/{userId}")
    public Result<RegistrationOutboxService.JobView> registrationJob(@PathVariable long userId) {
        StpUtil.checkRole("ADMIN");
        return Result.ok(registrationOutboxes.job(userId));
    }

    @PatchMapping("/registration-jobs/{userId}")
    public Result<RegistrationOutboxService.JobView> retryRegistrationJob(
            @PathVariable long userId,
            @Valid @RequestBody RegistrationJobPatch request) {
        StpUtil.checkRole("ADMIN");
        return Result.ok(registrationOutboxes.retryStuck(userId, StpUtil.getLoginIdAsLong()));
    }

    @PatchMapping("/stuck-reservations/{reservationNo}")
    public Result<StuckView> resolveStuck(
            @PathVariable long reservationNo,
            @Valid @RequestBody StuckReservationPatch request) {
        StpUtil.checkRole("ADMIN");
        long resolverId = StpUtil.getLoginIdAsLong();
        return Result.ok(toView(reconcileService.resolveStuck(reservationNo, request.status(), resolverId)));
    }

    public record StuckReservationPatch(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "ROLLED_BACK")
            String status) {
    }

    public record RegistrationJobPatch(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "PENDING")
            String status) {
    }

    public record StuckView(Long reservationNo, Long slotId, Integer status,
                            Integer reinjectCount, String lastError, LocalDateTime createAt) {
    }

    public record ReconciliationLogView(Long id, String taskType, String period, Long slotId,
                                        Integer redisOccupied, Integer dbOccupied,
                                        Integer reservationCnt, Integer diff, String fixAction,
                                        LocalDateTime createAt) {
    }

    private static StuckView toView(StuckReservation row) {
        return new StuckView(row.getReservationNo(), row.getSlotId(), row.getStatus(),
                row.getReinjectCount(), row.getLastError(), row.getCreateAt());
    }

    private static ReconciliationLogView toView(ReconcileLog row) {
        return new ReconciliationLogView(row.getId(), row.getTaskType(), row.getPeriod(),
                row.getSlotId(), row.getRedisOccupied(), row.getDbOccupied(),
                row.getReservationCnt(), row.getDiff(), row.getFixAction(), row.getCreateAt());
    }
}
