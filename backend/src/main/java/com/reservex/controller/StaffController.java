package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.ErrorCode;
import com.reservex.common.Result;
import com.reservex.service.QrService;
import com.reservex.service.ReconcileService;
import com.reservex.service.ReservationService;
import com.reservex.service.SlotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/staff")
@RequiredArgsConstructor
public class StaffController {

    private final QrService qrService;
    private final ReservationService reservationService;
    private final SlotService slotService;
    private final ReconcileService reconcileService;

    @PostMapping("/verify/scan")
    public Result<QrService.VerifyView> scan(@Valid @RequestBody ScanRequest request) {
        StpUtil.checkRole("STAFF");
        QrService.VerifyOutcome outcome = qrService.verifyScan(
                StpUtil.getLoginIdAsLong(), request.payload());
        return outcome.alreadyVerified()
                ? Result.failWithData(ErrorCode.ALREADY_VERIFIED, outcome.view())
                : Result.ok(outcome.view());
    }

    @PostMapping("/verify/manual")
    public Result<QrService.VerifyView> manual(@Valid @RequestBody ManualRequest request) {
        StpUtil.checkRole("STAFF");
        QrService.VerifyOutcome outcome = qrService.verifyManual(
                StpUtil.getLoginIdAsLong(), request.rno(), request.idCardMaskedConfirm());
        return outcome.alreadyVerified()
                ? Result.failWithData(ErrorCode.ALREADY_VERIFIED, outcome.view())
                : Result.ok(outcome.view());
    }

    /** 今日工作台:今日场次的预约列表(跨分库广播,低频管理操作可接受)。 */
    @GetMapping("/today")
    public Result<List<ReservationService.ReservationView>> today() {
        StpUtil.checkRole("STAFF");
        return Result.ok(reservationService.listToday(slotService));
    }

    /**
     * 今日核销统计:今日各状态预约计数 + 今日核销成功数 + 核销尝试流水数。
     * 用于 StaffToday 工作台指标卡片。
     */
    @GetMapping("/verify-stats")
    public Result<ReconcileService.VerifyStatsView> verifyStats() {
        StpUtil.checkRole("STAFF");
        return Result.ok(reconcileService.verifyStatsToday());
    }

    public record ScanRequest(@NotBlank @Size(max = 512) String payload) {
    }

    public record ManualRequest(@NotNull Long rno,
                                @NotBlank @Size(max = 32) String idCardMaskedConfirm) {
    }
}
