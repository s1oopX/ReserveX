package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.Result;
import com.reservex.entity.ReconcileLog;
import com.reservex.entity.StuckReservation;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.service.ReconcileService;
import com.reservex.service.SlotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOpsController {

    private final ReconcileService reconcileService;
    private final SlotService slotService;
    private final StuckReservationMapper stuckMapper;

    @GetMapping("/reconcile/diff")
    public Result<List<ReconcileLog>> diffs() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(reconcileService.diffs());
    }

    @GetMapping("/reconcile/latest")
    public Result<List<ReconcileLog>> latest() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(reconcileService.latest());
    }

    @GetMapping("/reconcile/stuck")
    public Result<List<StuckReservation>> stuck() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(reconcileService.stuck());
    }

    @GetMapping("/reconcile/dlq")
    public Result<DlqView> dlq() {
        StpUtil.checkRole("ADMIN");
        // DLQ 监控需 RocketMQ Dashboard / MQAdminExt,投入高收益低。
        // stuck_reservation 是 DLQ 的业务等价物(PendingScanner.toStuck 已存回滚所需全部参数),
        // 返回待研判卡单数作为代理指标,引导运维走 /reconcile/stuck 处置。
        long stuckCount = stuckMapper.selectCount(null);
        return Result.ok(new DlqView(List.of(), "stub",
                "DLQ 监控需 RocketMQ Dashboard;请用 /reconcile/stuck 查看待研判卡单", stuckCount));
    }

    @GetMapping("/dashboard")
    public Result<ReconcileService.Dashboard> dashboard() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(reconcileService.dashboard());
    }

    /**
     * 管理员全园预约查询(广播两库归并 + 脱敏)。
     * 参数皆可选:不传则返最近 500 条(按 create_at desc)。
     */
    @GetMapping("/reservations")
    public Result<List<ReconcileService.ReservationView>> reservations(
            @RequestParam(required = false) Long rno,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate slotDate,
            @RequestParam(required = false) String status) {
        StpUtil.checkRole("ADMIN");
        return Result.ok(reconcileService.listAdminReservations(rno, slotDate, status));
    }

    @GetMapping("/release-monitor")
    public Result<List<SlotService.ReleaseMonitorView>> releaseMonitor() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(slotService.listReleaseMonitor());
    }

    @PostMapping("/reconcile/{type}/action")
    public Result<Integer> reconcileAction(@PathVariable String type,
                                            @Valid @RequestBody ReconcileActionRequest request) {
        StpUtil.checkRole("ADMIN");
        long resolverId = StpUtil.getLoginIdAsLong();
        int affected = reconcileService.handleAction(type, request.id(), request.action(), resolverId);
        return Result.ok(affected);
    }

    public record DlqView(List<Object> items, String source, String reason, long stuckCount) {
    }

    public record ReconcileActionRequest(@NotNull Long id, @NotNull String action) {
    }
}
