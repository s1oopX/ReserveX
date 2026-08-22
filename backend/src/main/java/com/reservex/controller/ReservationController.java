package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.Result;
import com.reservex.common.HttpPreconditions;
import com.reservex.service.ReservationService;
import com.reservex.service.QrService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final QrService qrService;

    @PostMapping
    public ResponseEntity<Result<ReservationService.GrabResult>> grab(
            @Valid @RequestBody GrabRequest request) {
        StpUtil.checkRole("USER");
        ReservationService.GrabResult result = reservationService.grab(
                StpUtil.getLoginIdAsLong(), request.slotId(), request.captchaToken());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .header(HttpHeaders.LOCATION, "/api/reservations/" + result.reservationNo())
                .body(Result.ok(result));
    }

    @GetMapping
    public Result<List<ReservationService.ReservationView>> mine() {
        StpUtil.checkRole("USER");
        return Result.ok(reservationService.mine(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/{reservationNo}")
    public ResponseEntity<Result<ReservationService.ReservationView>> detail(
            @PathVariable long reservationNo) {
        StpUtil.checkRole("USER");
        ReservationService.ReservationView view = reservationService.detail(
                StpUtil.getLoginIdAsLong(), reservationNo);
        return ResponseEntity.ok()
                .eTag(HttpPreconditions.etag(view.version()))
                .body(Result.ok(view));
    }

    @PatchMapping("/{reservationNo}")
    public ResponseEntity<Result<ReservationService.ReservationView>> cancel(
            @PathVariable long reservationNo,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CancelRequest request) {
        StpUtil.checkRole("USER");
        reservationService.cancel(StpUtil.getLoginIdAsLong(), reservationNo,
                HttpPreconditions.requireVersion(ifMatch));
        ReservationService.ReservationView view = reservationService.detail(
                StpUtil.getLoginIdAsLong(), reservationNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"" + view.version() + "\"")
                .body(Result.ok(view));
    }

    @GetMapping("/{reservationNo}/qr")
    public Result<QrService.QrView> qr(@PathVariable long reservationNo) {
        StpUtil.checkRole("USER");
        return Result.ok(qrService.issue(StpUtil.getLoginIdAsLong(), reservationNo));
    }

    public record GrabRequest(@NotNull Long slotId, String captchaToken) {
    }

    public record CancelRequest(@NotNull @Pattern(regexp = "CANCELLED") String status) {
    }
}
