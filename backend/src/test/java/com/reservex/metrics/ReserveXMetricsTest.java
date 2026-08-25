package com.reservex.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReserveXMetricsTest {

    @Test
    void sameEventAccumulatesOnOneSeriesInsteadOfCreatingNewMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReserveXMetrics metrics = new ReserveXMetrics(registry);

        metrics.stuckIntake(ReserveXMetrics.REASON_USER_MISSING);
        metrics.stuckIntake(ReserveXMetrics.REASON_USER_MISSING);
        metrics.stuckIntake(ReserveXMetrics.REASON_REINJECT_EXHAUSTED);

        assertEquals(2d, registry.get(ReserveXMetrics.STUCK_INTAKE)
                .tag("reason", ReserveXMetrics.REASON_USER_MISSING).counter().count());
        assertEquals(1d, registry.get(ReserveXMetrics.STUCK_INTAKE)
                .tag("reason", ReserveXMetrics.REASON_REINJECT_EXHAUSTED).counter().count());
        // 两个 reason 各一条序列;重复调用不能每次新建 meter,否则抓取体积随流量增长。
        assertEquals(2, registry.find(ReserveXMetrics.STUCK_INTAKE).counters().size());
    }

    @Test
    void reinjectOutcomesShareOneNameSoFailureRatioIsComputable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReserveXMetrics metrics = new ReserveXMetrics(registry);

        metrics.reinjectSent();
        metrics.reinjectSent();
        metrics.reinjectFailed();

        // 同名 + outcome tag 才能在看板上直接算失败率;拆成两个指标名就得跨指标做除法。
        assertEquals(2d, registry.get(ReserveXMetrics.REINJECT_TOTAL)
                .tag("outcome", "sent").counter().count());
        assertEquals(1d, registry.get(ReserveXMetrics.REINJECT_TOTAL)
                .tag("outcome", "failed").counter().count());
    }

    @Test
    void everyCounterCarriesADescriptionForTheScrapeOutput() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReserveXMetrics metrics = new ReserveXMetrics(registry);

        metrics.stuckIntake(ReserveXMetrics.REASON_USER_MISSING);
        metrics.reinjectFailed();
        metrics.deadLetterCaptured("cg-persistence");
        metrics.mqSendFailed("reservation-created");
        metrics.compensateTriggered(ReserveXMetrics.REASON_ID_CARD_ROUTE_CONFLICT);

        List<Meter> meters = registry.getMeters();
        assertEquals(5, meters.size());
        for (Meter meter : meters) {
            // description 会变成 prometheus 的 HELP 行 —— 值班的人半夜看到指标名要能直接懂。
            String description = meter.getId().getDescription();
            assertNotNull(description, meter.getId().getName() + " 缺 description");
            assertTrue(meter.getId().getName().startsWith("reservex."),
                    meter.getId().getName() + " 不在 reservex 命名空间");
        }
    }

    @Test
    void noopDiscardsEverythingSoLegacyUnitTestsStayCheap() {
        ReserveXMetrics metrics = ReserveXMetrics.noop();

        // 不抛异常即达标:noop 只服务不关心埋点的单测,生产注入它等于把告警静默掉。
        metrics.stuckIntake(ReserveXMetrics.REASON_USER_MISSING);
        metrics.reinjectSent();
        metrics.deadLetterCaptured("cg-rollback");
        metrics.mqSendFailed("compensate-rollback");
        metrics.compensateTriggered(ReserveXMetrics.REASON_ID_CARD_ROUTE_CONFLICT);
    }
}
