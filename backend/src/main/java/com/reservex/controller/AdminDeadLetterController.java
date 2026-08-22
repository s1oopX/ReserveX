package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.Result;
import com.reservex.service.DeadLetterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/dead-letter-messages")
@RequiredArgsConstructor
public class AdminDeadLetterController {

    private final DeadLetterService deadLetters;

    @GetMapping
    public Result<List<DeadLetterService.View>> list() {
        StpUtil.checkRole("ADMIN");
        return Result.ok(deadLetters.list());
    }

    @PatchMapping("/{messageId}")
    public Result<DeadLetterService.View> replay(@PathVariable @NotBlank String messageId,
                                                 @Valid @RequestBody Patch request) {
        StpUtil.checkRole("ADMIN");
        return Result.ok(deadLetters.replay(messageId, StpUtil.getLoginIdAsLong()));
    }

    public record Patch(@NotBlank @Pattern(regexp = "REPLAYED") String status) {
    }
}
