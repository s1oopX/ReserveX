package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.Result;
import com.reservex.service.SlotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSlotController {

    private final SlotService slotService;

    @GetMapping("/slot-templates")
    public Result<List<SlotService.TemplateView>> templates() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(slotService.listTemplates());
    }

    @PostMapping("/slot-templates")
    public Result<SlotService.TemplateView> createTemplate(
            @Valid @RequestBody TemplateCreateRequest request) {
        StpUtil.checkRole("ADMIN");
        return Result.ok(slotService.createTemplate(new SlotService.TemplateInput(
                request.slotHour(), request.durationMin(), request.capacity(), request.bucketCount(),
                request.releaseOffsetMin(), request.enabled())));
    }

    @PutMapping("/slot-templates/{templateId}")
    public Result<SlotService.TemplateView> updateTemplate(
            @PathVariable long templateId, @RequestBody TemplateUpdateRequest request) {
        StpUtil.checkRole("ADMIN");
        return Result.ok(slotService.updateTemplate(templateId, new SlotService.TemplatePatch(
                request.slotHour(), request.durationMin(), request.capacity(), request.bucketCount(),
                request.releaseOffsetMin(), request.enabled(), request.version())));
    }

    @GetMapping("/slots")
    public Result<List<SlotService.AdminSlotView>> slots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        StpUtil.checkRole("ADMIN");
        return Result.ok(slotService.listAdminSlots(date));
    }

    @PostMapping("/slots/{slotId}/capacity")
    public Result<Void> increaseCapacity(@PathVariable long slotId,
                                          @Valid @RequestBody CapacityRequest request) {
        StpUtil.checkRole("ADMIN");
        slotService.increaseCapacity(slotId, request.delta(), request.version());
        return Result.ok(null);
    }

    public record CapacityRequest(@NotNull Integer delta, @NotNull Integer version) {
    }

    public record TemplateCreateRequest(
            @NotNull Integer slotHour,
            @NotNull Integer durationMin,
            @NotNull Integer capacity,
            @NotNull Integer bucketCount,
            @NotNull Integer releaseOffsetMin,
            boolean enabled) {
    }

    public record TemplateUpdateRequest(Integer slotHour, Integer durationMin, Integer capacity,
                                        Integer bucketCount, Integer releaseOffsetMin,
                                        Boolean enabled, @NotNull Integer version) {
    }
}
