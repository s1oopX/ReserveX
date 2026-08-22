package com.reservex.controller;

import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.Result;
import com.reservex.common.TimeSupport;
import com.reservex.service.SlotService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;
    private final TimeSupport time;

    @GetMapping
    public Result<List<SlotService.SlotView>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date.isBefore(time.today()) || date.isAfter(time.today().plusDays(7))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能查询今天起 7 天内的场次");
        }
        return Result.ok(slotService.listSlots(date));
    }

    @GetMapping("/{slotId}")
    public Result<SlotService.SlotView> detail(@PathVariable long slotId) {
        return Result.ok(slotService.getSlot(slotId));
    }
}
