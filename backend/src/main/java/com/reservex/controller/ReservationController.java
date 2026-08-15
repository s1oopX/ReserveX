package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.Result;
import com.reservex.service.ReservationService;
import com.reservex.service.QrService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reservation")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final QrService qrService;

    @PostMapping("/grab")
    public Result<ReservationService.GrabResult> grab(@Valid @RequestBody GrabRequest request) {
        StpUtil.checkRole("USER");
        return Result.ok(reservationService.grab(StpUtil.getLoginIdAsLong(), request.slotId(), request.captchaToken()));
    }

    @GetMapping("/mine")
    public Result<List<ReservationService.ReservationView>> mine() {
        StpUtil.checkRole("USER");
        return Result.ok(reservationService.mine(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/{reservationNo}")
    public Result<ReservationService.ReservationView> detail(@PathVariable long reservationNo) {
        StpUtil.checkRole("USER");
        return Result.ok(reservationService.detail(StpUtil.getLoginIdAsLong(), reservationNo));
    }

    @PostMapping("/{reservationNo}/cancel")
    public Result<Void> cancel(@PathVariable long reservationNo) {
        StpUtil.checkRole("USER");
        reservationService.cancel(StpUtil.getLoginIdAsLong(), reservationNo);
        return Result.ok();
    }

    @GetMapping("/{reservationNo}/qr")
    public Result<QrService.QrView> qr(@PathVariable long reservationNo) {
        StpUtil.checkRole("USER");
        return Result.ok(qrService.issue(StpUtil.getLoginIdAsLong(), reservationNo));
    }

    public record GrabRequest(@NotNull Long slotId, String captchaToken) {
    }
}
