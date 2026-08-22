package com.reservex.controller;

import com.reservex.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiRootController {

    @GetMapping({"", "/"})
    public Result<ApiRoot> root() {
        return Result.ok(new ApiRoot("v1", Map.ofEntries(
                Map.entry("users", "/api/users"),
                Map.entry("registrations", "/api/registrations/{registrationKey}"),
                Map.entry("sessions", "/api/sessions/current"),
                Map.entry("emailVerifications", "/api/email-verifications"),
                Map.entry("captchas", "/api/captchas"),
                Map.entry("slots", "/api/slots"),
                Map.entry("reservations", "/api/reservations"),
                Map.entry("staffMembers", "/api/staff-members"),
                Map.entry("slotTemplates", "/api/admin/slot-templates"),
                Map.entry("reconciliationLogs", "/api/admin/reconciliation-logs"),
                Map.entry("registrationJobs", "/api/admin/registration-jobs/{userId}"),
                Map.entry("stuckReservations", "/api/admin/stuck-reservations"),
                Map.entry("deadLetterMessages", "/api/admin/dead-letter-messages"))));
    }

    public record ApiRoot(String version, Map<String, String> links) {
    }
}
