package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.Result;
import com.reservex.common.HttpPreconditions;
import com.reservex.service.SlotService;
import com.reservex.service.SlotTemplateAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSlotController {

    private final SlotService slotService;
    private final SlotTemplateAdminService templateService;

    @GetMapping("/slot-templates")
    public Result<List<SlotTemplateAdminService.TemplateView>> templates() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(templateService.list());
    }

    @GetMapping("/slot-templates/{templateId}")
    public ResponseEntity<Result<SlotTemplateAdminService.TemplateView>> template(
            @PathVariable long templateId) {
        StpUtil.checkRole("ADMIN");
        var view = templateService.get(templateId);
        return ResponseEntity.ok()
                .eTag(HttpPreconditions.etag(view.version()))
                .body(Result.ok(view));
    }

    @PostMapping("/slot-templates")
    public ResponseEntity<Result<SlotTemplateAdminService.TemplateView>> createTemplate(
            @Valid @RequestBody TemplateCreateRequest request) {
        StpUtil.checkRole("ADMIN");
        var created = templateService.create(new SlotTemplateAdminService.TemplateInput(
                request.slotHour(), request.durationMin(), request.capacity(), request.bucketCount(),
                request.releaseOffsetMin(), request.enabled()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/admin/slot-templates/" + created.templateId())
                .header(HttpHeaders.ETAG, "\"" + created.version() + "\"")
                .body(Result.ok(created));
    }

    @PatchMapping("/slot-templates/{templateId}")
    public ResponseEntity<Result<SlotTemplateAdminService.TemplateView>> updateTemplate(
            @PathVariable long templateId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody TemplateUpdateRequest request) {
        StpUtil.checkRole("ADMIN");
        var updated = templateService.update(templateId,
                new SlotTemplateAdminService.TemplatePatch(request.slotHour(), request.durationMin(),
                        request.capacity(), request.bucketCount(), request.releaseOffsetMin(),
                        request.enabled()), HttpPreconditions.requireVersion(ifMatch));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"" + updated.version() + "\"")
                .body(Result.ok(updated));
    }

    @GetMapping("/slots")
    public Result<List<SlotService.AdminSlotView>> slots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        StpUtil.checkRole("ADMIN");
        return Result.ok(slotService.listAdminSlots(date));
    }

    @GetMapping("/slots/{slotId}")
    public ResponseEntity<Result<SlotResource>> slot(@PathVariable long slotId) {
        StpUtil.checkRole("ADMIN");
        SlotService.AdminSlotView view = slotService.getAdminSlot(slotId);
        return ResponseEntity.ok()
                .eTag(HttpPreconditions.etag(view.version()))
                .body(Result.ok(SlotResource.from(view)));
    }

    @PatchMapping("/slots/{slotId}")
    public ResponseEntity<Result<SlotResource>> setCapacity(
            @PathVariable long slotId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CapacityRequest request) {
        StpUtil.checkRole("ADMIN");
        SlotService.AdminSlotView updated = slotService.setCapacity(
                slotId, request.capacity(), HttpPreconditions.requireVersion(ifMatch),
                StpUtil.getLoginIdAsLong());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"" + updated.version() + "\"")
                .body(Result.ok(SlotResource.from(updated)));
    }

    public record CapacityRequest(@NotNull Integer capacity) {
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
                                        Boolean enabled) {
    }

    /** Versioned representation; live Redis fields belong to the collection monitor view. */
    public record SlotResource(Long slotId, Long templateId, LocalDate slotDate, Integer slotHour,
                               Integer durationMin, java.time.LocalDateTime validUntil,
                               Integer capacity, Integer bucketCount, boolean released,
                               java.time.LocalDateTime releaseAt, Integer version) {
        static SlotResource from(SlotService.AdminSlotView view) {
            return new SlotResource(view.slotId(), view.templateId(), view.slotDate(),
                    view.slotHour(), view.durationMin(), view.validUntil(), view.capacity(),
                    view.bucketCount(), view.released(), view.releaseAt(), view.version());
        }
    }
}
