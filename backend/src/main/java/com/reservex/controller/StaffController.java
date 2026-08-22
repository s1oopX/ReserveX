package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.Result;
import com.reservex.service.QrService;
import com.reservex.service.ReconcileService;
import com.reservex.service.ReservationService;
import com.reservex.service.SlotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/verifications")
    public ResponseEntity<Result<QrService.VerifyView>> verify(
            @Valid @RequestBody VerificationRequest request) {
        StpUtil.checkRole("STAFF");
        QrService.VerifyOutcome outcome = switch (request.method()) {
            case "QR" -> {
                if (request.payload() == null || request.payload().isBlank()) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "QR 载荷不能为空");
                }
                yield qrService.verifyScan(StpUtil.getLoginIdAsLong(), request.payload());
            }
            case "MANUAL" -> {
                if (request.rno() == null || request.idCardLast4() == null
                        || request.idCardLast4().isBlank()) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "手工核销参数不完整");
                }
                yield qrService.verifyManual(StpUtil.getLoginIdAsLong(), request.rno(),
                        request.idCardLast4());
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的核销方式");
        };
        return verifyResponse(outcome);
    }

    private ResponseEntity<Result<QrService.VerifyView>> verifyResponse(QrService.VerifyOutcome outcome) {
        Result<QrService.VerifyView> body = outcome.alreadyVerified()
                ? Result.failWithData(ErrorCode.ALREADY_VERIFIED, outcome.view())
                : Result.ok(outcome.view());
        return ResponseEntity.status(outcome.alreadyVerified() ? HttpStatus.CONFLICT : HttpStatus.OK)
                .body(body);
    }

    /** 今日工作台:今日场次的预约列表(跨分库广播,低频管理操作可接受)。 */
    @GetMapping("/reservations")
    public Result<List<ReservationService.StaffReservationView>> today() {
        StpUtil.checkRole("STAFF");
        return Result.ok(reservationService.listToday(slotService));
    }

    /**
     * 今日核销统计:今日各状态预约计数 + 今日核销成功数 + 核销尝试流水数。
     * 用于 StaffToday 工作台指标卡片。
     */
    @GetMapping("/verification-statistics")
    public Result<ReconcileService.VerifyStatsView> verifyStats() {
        StpUtil.checkRole("STAFF");
        return Result.ok(reconcileService.verifyStatsToday());
    }

    public record VerificationRequest(
            @NotBlank @Pattern(regexp = "QR|MANUAL") String method,
            @Size(max = 512) String payload,
            Long rno,
            @Pattern(regexp = "[0-9]{3}[0-9Xx]") String idCardLast4) {
    }
}
