package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.mapper.single.RegistrationOutboxMapper;
import com.reservex.service.RegistrationOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically claims and drains registrations left by a crashed request. */
@Slf4j
@Component
public class RegistrationOutboxWorker {

    private final RegistrationOutboxMapper outboxes;
    private final RegistrationOutboxService service;
    private final TimeSupport time;

    public RegistrationOutboxWorker(RegistrationOutboxMapper outboxes,
                                    RegistrationOutboxService service, TimeSupport time) {
        this.outboxes = outboxes;
        this.service = service;
        this.time = time;
    }

    @Scheduled(cron = "${reservex.reconcile.crons.registration-outbox:*/10 * * * * ?}")
    public void drain() {
        for (Long userId : outboxes.selectDueUserIds(time.now(), 200)) {
            try {
                service.ensureUser(userId);
            } catch (RuntimeException e) {
                log.error("注册 outbox 处理异常 userId={}", userId, e);
            }
        }
    }
}
