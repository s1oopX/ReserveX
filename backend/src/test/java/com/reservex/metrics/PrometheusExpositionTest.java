package com.reservex.metrics;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.StuckReservation;
import com.reservex.mapper.single.DeadLetterMessageMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 指标埋了不等于告警能用:PromQL 里写的是 Prometheus 改写后的名字
 * ({@code reservex.stuck.intake} → {@code reservex_stuck_intake_total}),
 * 名字对不上时规则不会报错,只会永远不触发 —— 正是本轮要消灭的那种「静默」。
 * 这里把抓取输出里的名字钉死,好让后续 alert rules 有据可依。
 */
class PrometheusExpositionTest {

    @BeforeAll
    static void registerTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), StuckReservation.class);
    }

    @Test
    void countersExposeTheNamesAlertRulesWillQuery() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ReserveXMetrics metrics = new ReserveXMetrics(registry);

        metrics.stuckIntake(ReserveXMetrics.REASON_REINJECT_EXHAUSTED);
        metrics.reinjectFailed();
        metrics.deadLetterCaptured("reservation-persist-group");
        metrics.mqSendFailed("reservation-created");
        metrics.compensateTriggered(ReserveXMetrics.REASON_ID_CARD_ROUTE_CONFLICT);

        String scrape = registry.scrape();
        // counter 会被加上 _total 后缀,规则里漏了它就永远匹配不到任何序列。
        assertTrue(scrape.contains("reservex_stuck_intake_total{reason=\"reinject-exhausted\"}"), scrape);
        assertTrue(scrape.contains("reservex_reinject_total{outcome=\"failed\"}"), scrape);
        assertTrue(scrape.contains(
                "reservex_deadletter_captured_total{source_group=\"reservation-persist-group\"}"), scrape);
        assertTrue(scrape.contains("reservex_mq_send_failed_total{topic=\"reservation-created\"}"), scrape);
        assertTrue(scrape.contains(
                "reservex_compensate_triggered_total{reason=\"id-card-route-conflict\"}"), scrape);
        // description 变成 HELP 行:值班的人半夜看到指标名,得能知道它是什么。
        assertTrue(scrape.contains("# HELP reservex_stuck_intake_total"), scrape);
    }

    @Test
    void gaugesExposeBacklogNamesAndSurviveABrokenQuery() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        DeadLetterMessageMapper deadLetters = mock(DeadLetterMessageMapper.class);
        ReconcileLogMapper reconcile = mock(ReconcileLogMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.zCard(any())).thenReturn(11L);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 25, 14, 30));
        when(time.today()).thenReturn(LocalDateTime.of(2026, 8, 25, 14, 30).toLocalDate());
        // 卡单两个 gauge 走同一个 mapper,让它炸,验证抓取整体不受影响。
        when(stuck.selectCount(any())).thenThrow(new QueryTimeoutException("db down"));
        when(deadLetters.selectCount(any())).thenReturn(3L);
        when(reconcile.countCurrentWithDiff(any())).thenReturn(2L);

        new ReserveXHealthGauges(registry, stuck, deadLetters, reconcile, redis,
                new ReserveXProperties(), time);
        String scrape = registry.scrape();

        // gauge 不加后缀,名字就是点换下划线。
        assertTrue(scrape.contains("reservex_deadletter_pending 3.0"), scrape);
        assertTrue(scrape.contains("reservex_reconcile_diff_current 2.0"), scrape);
        assertTrue(scrape.contains("reservex_pending_persist_backlog 11.0"), scrape);
        // 一个查询挂掉只该让它自己变 NaN,不该把整个 /actuator/prometheus 打成 500。
        assertTrue(scrape.contains("reservex_stuck_pending NaN"), scrape);
        assertTrue(scrape.contains("reservex_stuck_overdue NaN"), scrape);
        assertFalse(scrape.isBlank());
    }
}
