package com.reservex.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.reservex.common.ErrorCode;
import com.reservex.common.HttpPreconditions;
import com.reservex.entity.ReconcileLog;
import com.reservex.service.AdminReservationQueryService;
import com.reservex.service.AuthService;
import com.reservex.service.CaptchaService;
import com.reservex.service.QrService;
import com.reservex.service.ReconcileService;
import com.reservex.service.ReservationService;
import com.reservex.service.RegistrationCodeService;
import com.reservex.service.RegistrationOutboxService;
import com.reservex.service.SlotService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ControllerContractTest {

    @Test
    void routeInventoryIsResourceOrientedAndStable() throws Exception {
        assertThat(routes()).isEqualTo(Set.of(
                "GET /api", "GET /api/",
                "POST /api/email-verifications", "POST /api/users", "POST /api/sessions",
                "GET /api/users/{userId}", "GET /api/users/me", "GET /api/sessions/current",
                "GET /api/registrations/{registrationKey}",
                "PATCH /api/sessions/current", "DELETE /api/sessions/current",
                "PATCH /api/users/me", "POST /api/captchas",
                "GET /api/slots", "GET /api/slots/{slotId}",
                "POST /api/reservations", "GET /api/reservations",
                "GET /api/reservations/{reservationNo}",
                "PATCH /api/reservations/{reservationNo}",
                "GET /api/reservations/{reservationNo}/qr",
                "POST /api/staff/verifications", "GET /api/staff/reservations",
                "GET /api/staff/verification-statistics",
                "GET /api/staff-members", "POST /api/staff-members",
                "GET /api/staff-members/{userId}",
                "PATCH /api/staff-members/{userId}",
                "GET /api/admin/slot-templates", "POST /api/admin/slot-templates",
                "GET /api/admin/slot-templates/{templateId}",
                "PATCH /api/admin/slot-templates/{templateId}",
                "GET /api/admin/slots", "GET /api/admin/slots/{slotId}",
                "PATCH /api/admin/slots/{slotId}",
                "GET /api/admin/reconciliation-logs", "GET /api/admin/stuck-reservations",
                "PATCH /api/admin/stuck-reservations/{reservationNo}",
                "GET /api/admin/dashboard", "GET /api/admin/reservations",
                "GET /api/admin/release-monitor",
                "GET /api/admin/registration-jobs/{userId}",
                "PATCH /api/admin/registration-jobs/{userId}",
                "GET /api/admin/dead-letter-messages",
                "PATCH /api/admin/dead-letter-messages/{messageId}"));
        assertThat(routes()).noneMatch(route -> route.matches(
                ".*/(grab|cancel|login|logout|refresh|verify|increase|action)(/.*)?$"));
    }

    @Test
    void passwordChangeRequiredIsForbiddenWithTokenData() {
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(auth.login("staff@example.com", "password", "203.0.113.10"))
                .thenReturn(AuthService.LoginOutcome.passwordChangeRequired("once-token"));

        var response = new AuthController(auth, mock(RegistrationCodeService.class)).login(
                new AuthController.LoginRequest("staff@example.com", "password"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.PASSWORD_CHANGE_REQUIRED.getCode());
        assertThat(response.getBody().getData()).isEqualTo(
                new AuthController.PasswordChangeRequired("once-token"));
    }

    @Test
    void repeatedVerificationIsConflictWithOriginalView() {
        QrService qr = mock(QrService.class);
        var view = new QrService.VerifyView(42L, "ALREADY_VERIFIED",
                LocalDateTime.of(2026, 8, 17, 12, 0), 7L);
        when(qr.verifyScan(7L, "payload")).thenReturn(new QrService.VerifyOutcome(true, view));
        when(qr.verifyManual(7L, 42L, "1234"))
                .thenReturn(new QrService.VerifyOutcome(true, view));
        var controller = new StaffController(qr, mock(ReservationService.class),
                mock(SlotService.class), mock(ReconcileService.class));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var response = controller.verify(new StaffController.VerificationRequest(
                    "QR", "payload", null, null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.ALREADY_VERIFIED.getCode());
            assertThat(response.getBody().getData()).isEqualTo(view);

            var manual = controller.verify(new StaffController.VerificationRequest(
                    "MANUAL", null, 42L, "1234"));
            assertThat(manual.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(manual.getBody().getData()).isEqualTo(view);
        }
    }

    @Test
    void successfulVerificationReturns200WithoutDanglingLocation() {
        QrService qr = mock(QrService.class);
        var view = new QrService.VerifyView(42L, "VERIFIED",
                LocalDateTime.of(2026, 8, 17, 12, 0), 7L);
        when(qr.verifyScan(7L, "payload")).thenReturn(new QrService.VerifyOutcome(false, view));
        var controller = new StaffController(qr, mock(ReservationService.class),
                mock(SlotService.class), mock(ReconcileService.class));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var response = controller.verify(new StaffController.VerificationRequest(
                    "QR", "payload", null, null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getLocation()).isNull();
        }
    }

    @Test
    void captchaChallengeUsesPostAnd200() throws Exception {
        CaptchaService captcha = mock(CaptchaService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(captcha.generate("203.0.113.10"))
                .thenReturn(new CaptchaService.CaptchaView("key", "image"));

        var response = new CaptchaController(captcha).generate(request);
        var method = CaptchaController.class.getMethod("generate", HttpServletRequest.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getLocation()).isNull();
        assertThat(method.isAnnotationPresent(PostMapping.class)).isTrue();
        assertThat(method.isAnnotationPresent(GetMapping.class)).isFalse();
    }

    @Test
    void reconciliationResponseUsesAnExplicitView() {
        ReconcileService reconcile = mock(ReconcileService.class);
        ReconcileLog row = new ReconcileLog();
        row.setId(1L);
        row.setTaskType("stock");
        row.setPeriod("202608190000");
        row.setDiff(0);
        when(reconcile.diffs()).thenReturn(List.of(row));
        var controller = new AdminOpsController(reconcile, mock(SlotService.class),
                mock(AdminReservationQueryService.class), mock(RegistrationOutboxService.class));

        try (MockedStatic<StpUtil> ignored = mockStatic(StpUtil.class)) {
            Object item = controller.reconciliationLogs("current").getData().getFirst();
            assertThat(item).isInstanceOf(AdminOpsController.ReconciliationLogView.class)
                    .isNotInstanceOf(ReconcileLog.class);
        }
    }

    @Test
    void staffCreationReturns201AndTheCreatedUserId() {
        AuthService auth = mock(AuthService.class);
        when(auth.createStaff("staff@example.com", "13800138000", "password-1",
                "11010519491231002X", 7L, "01234567-89ab-cdef-0123-456789abcdef"))
                .thenReturn(new AuthService.RegistrationOutcome(42L, true));
        var controller = new AdminStaffController(auth);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var response = controller.create("01234567-89ab-cdef-0123-456789abcdef",
                    new AdminStaffController.CreateStaffRequest(
                    "staff@example.com", "13800138000", "password-1", "11010519491231002X"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getHeaders().getLocation()).hasPath("/api/staff-members/42");
            assertThat(response.getBody().getData())
                    .isEqualTo(new AdminStaffController.CreatedStaff(42L, true));
        }
    }

    @Test
    void pendingStaffCreationReturns202AndRegistrationJobLocation() {
        AuthService auth = mock(AuthService.class);
        when(auth.createStaff("staff@example.com", "13800138000", "password-1",
                "11010519491231002X", 7L, "01234567-89ab-cdef-0123-456789abcdef"))
                .thenReturn(new AuthService.RegistrationOutcome(42L, false));
        var controller = new AdminStaffController(auth);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var response = controller.create("01234567-89ab-cdef-0123-456789abcdef",
                    new AdminStaffController.CreateStaffRequest(
                    "staff@example.com", "13800138000", "password-1", "11010519491231002X"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getHeaders().getLocation())
                    .hasPath("/api/admin/registration-jobs/42");
            assertThat(response.getBody().getData())
                    .isEqualTo(new AdminStaffController.CreatedStaff(42L, false));
        }
    }

    @Test
    void userAndTemplateCreationLocationsHaveGetResources() throws Exception {
        AuthService auth = mock(AuthService.class);
        RegistrationCodeService codes = mock(RegistrationCodeService.class);
        when(auth.registrationRequestFingerprint("user@example.com", "13800138000", "password-1",
                "11010519491231002X")).thenReturn("request-fingerprint");
        when(codes.consumeForRegistration("user@example.com", "123456", "203.0.113.10",
                "01234567-89ab-cdef-0123-456789abcdef", "request-fingerprint")).thenReturn(true);
        when(auth.registerUserOutcome("user@example.com", "13800138000", "password-1",
                "11010519491231002X", "203.0.113.10",
                "01234567-89ab-cdef-0123-456789abcdef", true))
                .thenReturn(new AuthService.RegistrationOutcome(42L, true));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(request.getHeader("Idempotency-Key"))
                .thenReturn("01234567-89ab-cdef-0123-456789abcdef");
        var user = new AuthController(auth, codes).register(
                new AuthController.RegisterRequest("user@example.com", "123456", "13800138000",
                        "password-1", "11010519491231002X"), request);

        SlotService slots = mock(SlotService.class);
        var template = new com.reservex.service.SlotTemplateAdminService.TemplateView(
                9L, 9, 60, 100, 10, -60, true, 0);
        var templates = mock(com.reservex.service.SlotTemplateAdminService.class);
        when(templates.create(any())).thenReturn(template);
        org.springframework.http.ResponseEntity<com.reservex.common.Result<com.reservex.service.SlotTemplateAdminService.TemplateView>> createdTemplate;
        try (MockedStatic<StpUtil> ignored = mockStatic(StpUtil.class)) {
            createdTemplate = new AdminSlotController(slots, templates).createTemplate(
                    new AdminSlotController.TemplateCreateRequest(9, 60, 100, 10, -60, true));
        }

        assertThat(user.getHeaders().getLocation()).hasPath("/api/users/42");
        assertThat(createdTemplate.getHeaders().getLocation())
                .hasPath("/api/admin/slot-templates/9");
        assertThat(routes()).contains("GET /api/users/{userId}",
                "GET /api/admin/slot-templates/{templateId}");
    }

    @Test
    void registrationRequiresAnIdempotencyKeyBeforeConsumingTheEmailCode() {
        RegistrationCodeService codes = mock(RegistrationCodeService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        AuthController controller = new AuthController(mock(AuthService.class), codes);

        com.reservex.common.BizException error = org.junit.jupiter.api.Assertions.assertThrows(
                com.reservex.common.BizException.class,
                () -> controller.register(new AuthController.RegisterRequest(
                        "user@example.com", "123456", "13800138000",
                        "password-1", "11010519491231002X"), request));

        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        verifyNoInteractions(codes);
    }

    @Test
    void missingIfMatchIsRejectedBeforeCapacityMutation() {
        SlotService slots = mock(SlotService.class);
        com.reservex.common.BizException error;
        try (MockedStatic<StpUtil> ignored = mockStatic(StpUtil.class)) {
            error = org.junit.jupiter.api.Assertions.assertThrows(
                    com.reservex.common.BizException.class,
                    () -> new AdminSlotController(slots,
                            mock(com.reservex.service.SlotTemplateAdminService.class)).setCapacity(9L, null,
                            new AdminSlotController.CapacityRequest(20)));
        }

        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_REQUIRED);
        verifyNoInteractions(slots);
    }

    @Test
    void capacityChangeAttributesTheAuditToTheAuthenticatedAdmin() {
        SlotService slots = mock(SlotService.class);
        var updated = new SlotService.AdminSlotView(9L, 1L, java.time.LocalDate.of(2026, 8, 18),
                10, 60, LocalDateTime.of(2026, 8, 18, 11, 0), 20,
                4, true, LocalDateTime.of(2026, 8, 18, 9, 0), 3, 10, true);
        var condition = HttpPreconditions.requireVersion("\"2\"");
        when(slots.setCapacity(9L, 20, condition, 7L)).thenReturn(updated);
        var controller = new AdminSlotController(slots,
                mock(com.reservex.service.SlotTemplateAdminService.class));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var response = controller.setCapacity(9L, "\"2\"",
                    new AdminSlotController.CapacityRequest(20));

            assertThat(response.getBody().getData().version()).isEqualTo(3);
            assertThat(response.getHeaders().getETag()).isEqualTo("\"3\"");
            verify(slots).setCapacity(9L, 20, condition, 7L);
        }
    }

    @Test
    void versionedSlotResourceExcludesLiveRedisFields() {
        SlotService slots = mock(SlotService.class);
        var view = new SlotService.AdminSlotView(9L, 1L, java.time.LocalDate.of(2026, 8, 18),
                10, 60, LocalDateTime.of(2026, 8, 18, 11, 0), 20,
                4, true, LocalDateTime.of(2026, 8, 18, 9, 0), 3, 7, false);
        when(slots.getAdminSlot(9L)).thenReturn(view);

        org.springframework.http.ResponseEntity<com.reservex.common.Result<AdminSlotController.SlotResource>> response;
        try (MockedStatic<StpUtil> ignored = mockStatic(StpUtil.class)) {
            response = new AdminSlotController(slots,
                    mock(com.reservex.service.SlotTemplateAdminService.class)).slot(9L);
        }

        assertThat(response.getHeaders().getETag()).isEqualTo("\"3\"");
        assertThat(response.getBody().getData()).isEqualTo(
                AdminSlotController.SlotResource.from(view));
        assertThat(Arrays.stream(AdminSlotController.SlotResource.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("remain", "metaPresent");
    }

    @Test
    void sessionCreationReturns201LocationAndNoStore() {
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(auth.login("user@example.com", "password", "203.0.113.10"))
                .thenReturn(AuthService.LoginOutcome.authenticated(
                        new AuthService.TokenPair("access", "refresh", 7L, "USER")));

        var response = new AuthController(auth, mock(RegistrationCodeService.class)).login(
                new AuthController.LoginRequest("user@example.com", "password"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasPath("/api/sessions/current");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("reservex_refresh=refresh")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/api/sessions");
        assertThat(response.getBody().getData())
                .isEqualTo(new AuthController.SessionView("access", 7L, "USER"));
    }

    @Test
    void sessionRefreshReadsTheHttpOnlyCookieAndRotatesIt() {
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(request.getHeader("Authorization")).thenReturn("Bearer old-access");
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie("reservex_refresh", "old-refresh")});
        when(auth.refresh("old-refresh", "old-access", "203.0.113.10"))
                .thenReturn(new AuthService.TokenPair("new-access", "new-refresh", 7L, "USER"));

        var response = new AuthController(auth, mock(RegistrationCodeService.class)).refresh(request);

        assertThat(response.getBody().getData())
                .isEqualTo(new AuthController.SessionView("new-access", 7L, "USER"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("reservex_refresh=new-refresh")
                .contains("HttpOnly");
    }

    @Test
    void reservationCreationDistinguishesCreatedFromIdempotentReplay() {
        ReservationService reservations = mock(ReservationService.class);
        when(reservations.grab(7L, 9L, null))
                .thenReturn(new ReservationService.GrabResult(42L, true),
                        new ReservationService.GrabResult(42L, false));
        var controller = new ReservationController(reservations, mock(QrService.class));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var created = controller.grab(new ReservationController.GrabRequest(9L, null));
            var replay = controller.grab(new ReservationController.GrabRequest(9L, null));

            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(created.getHeaders().getLocation()).hasPath("/api/reservations/42");
            assertThat(replay.getHeaders().getLocation()).hasPath("/api/reservations/42");
        }
    }

    @Test
    void apiRootAdvertisesCanonicalCollectionUris() {
        var root = new ApiRootController().root().getData();

        assertThat(root.links()).containsEntry("users", "/api/users")
                .containsEntry("registrations", "/api/registrations/{registrationKey}")
                .containsEntry("reservations", "/api/reservations")
                .containsEntry("stuckReservations", "/api/admin/stuck-reservations");
    }

    private static Set<String> routes() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> routes = new TreeSet<>();
        for (var candidate : scanner.findCandidateComponents("com.reservex.controller")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequestMapping type = AnnotatedElementUtils.findMergedAnnotation(
                    controller, RequestMapping.class);
            String[] prefixes = paths(type);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                        method, RequestMapping.class);
                if (mapping == null || mapping.method().length == 0) {
                    continue;
                }
                for (String prefix : prefixes) {
                    for (String suffix : paths(mapping)) {
                        for (RequestMethod verb : mapping.method()) {
                            routes.add(verb.name() + " " + join(prefix, suffix));
                        }
                    }
                }
            }
        }
        return routes;
    }

    private static String[] paths(RequestMapping mapping) {
        if (mapping == null) {
            return new String[]{""};
        }
        String[] paths = mapping.path().length == 0 ? mapping.value() : mapping.path();
        return paths.length == 0 ? new String[]{""} : Arrays.stream(paths).toArray(String[]::new);
    }

    private static String join(String prefix, String suffix) {
        if (suffix.isEmpty()) {
            return prefix;
        }
        return prefix.endsWith("/") || suffix.startsWith("/")
                ? prefix + suffix : prefix + "/" + suffix;
    }
}
