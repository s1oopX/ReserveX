package com.reservex.metrics;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.StuckReservation;
import com.reservex.mapper.single.DeadLetterMessageMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.service.ReservationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReserveXHealthGaugesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 14, 30);

    /**
     * {@code LambdaQueryWrapper} 把列名和参数都存成延迟 lambda,
     * {@code getParamNameValuePairs()} 要等 SQL 渲染才有内容,而渲染需要实体的
     * 表元信息 —— 平时由 MyBatis 启动时注册,纯单测里没有。这里手动注册一次,
     * 好让下面能断言真实渲染出的 SQL 和参数,而不是只断言「调了 selectCount」。
     */
    @BeforeAll
    static void registerTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), StuckReservation.class);
    }

    @Test
    void gaugesReportCurrentBacklogNotJustIncrements() {
        Fixture fixture = fixture();
        when(fixture.stuck.selectCount(any())).thenReturn(7L);
        when(fixture.deadLetters.selectCount(any())).thenReturn(3L);
        when(fixture.reconcile.countCurrentWithDiff(LocalDate.of(2026, 8, 25))).thenReturn(2L);
        when(fixture.zset.zCard(ReservationService.PENDING_KEY)).thenReturn(11L);

        new ReserveXHealthGauges(fixture.registry, fixture.stuck, fixture.deadLetters,
                fixture.reconcile, fixture.redis, fixture.props, fixture.time);

        assertEquals(7d, gauge(fixture, ReserveXHealthGauges.STUCK_PENDING));
        assertEquals(3d, gauge(fixture, ReserveXHealthGauges.DEADLETTER_PENDING));
        assertEquals(2d, gauge(fixture, ReserveXHealthGauges.RECONCILE_DIFF));
        assertEquals(11d, gauge(fixture, ReserveXHealthGauges.PENDING_PERSIST_BACKLOG));
    }

    @Test
    void overdueGaugeCutsOffAtTheConfiguredStuckAlertWindow() {
        Fixture fixture = fixture();
        fixture.props.getPending().setStuckAlertAfterMin(90);
        when(fixture.stuck.selectCount(any())).thenReturn(1L);

        new ReserveXHealthGauges(fixture.registry, fixture.stuck, fixture.deadLetters,
                fixture.reconcile, fixture.redis, fixture.props, fixture.time);
        gauge(fixture, ReserveXHealthGauges.STUCK_OVERDUE);

        // stuck-alert-after-min 长期只写在文档里没人读;这里钉住它真的进了查询条件。
        // getParamNameValuePairs 在 AbstractWrapper 上,不在 Wrapper 接口上,所以按具体类型捕获。
        ArgumentCaptor<LambdaQueryWrapper<StuckReservation>> captured = captor();
        verify(fixture.stuck).selectCount(captured.capture());
        LambdaQueryWrapper<StuckReservation> wrapper = captured.getValue();
        String sql = wrapper.getSqlSegment();          // 渲染后参数才会落进 map
        assertTrue(sql.contains("create_at"), "逾期判据没有落在 create_at 上, 实际 SQL=" + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(NOW.minusMinutes(90)),
                "逾期判据没有用 stuck-alert-after-min 派生的 cutoff, 实际参数="
                        + wrapper.getParamNameValuePairs());
    }

    @Test
    void databaseFailureYieldsNaNInsteadOfBreakingTheWholeScrape() {
        Fixture fixture = fixture();
        when(fixture.stuck.selectCount(any()))
                .thenThrow(new QueryTimeoutException("db down"));
        when(fixture.deadLetters.selectCount(any())).thenReturn(4L);

        new ReserveXHealthGauges(fixture.registry, fixture.stuck, fixture.deadLetters,
                fixture.reconcile, fixture.redis, fixture.props, fixture.time);

        // gauge 在抓取线程上求值:抛出去会让 /actuator/prometheus 整体 500,
        // 故障时连 JVM/Hikari 指标一起看不见。NaN 在 Prometheus 里是「未知」,不会误报。
        assertTrue(Double.isNaN(gauge(fixture, ReserveXHealthGauges.STUCK_PENDING)));
        assertEquals(4d, gauge(fixture, ReserveXHealthGauges.DEADLETTER_PENDING));
    }

    @Test
    void redisFailureOnlyBlindsTheBacklogGauge() {
        Fixture fixture = fixture();
        when(fixture.zset.zCard(ReservationService.PENDING_KEY))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        when(fixture.stuck.selectCount(any())).thenReturn(0L);

        new ReserveXHealthGauges(fixture.registry, fixture.stuck, fixture.deadLetters,
                fixture.reconcile, fixture.redis, fixture.props, fixture.time);

        assertTrue(Double.isNaN(gauge(fixture, ReserveXHealthGauges.PENDING_PERSIST_BACKLOG)));
        assertEquals(0d, gauge(fixture, ReserveXHealthGauges.STUCK_PENDING));
    }

    @Test
    void repeatedScrapesWithinTheCacheWindowDoNotReReadTheDatabase() {
        Fixture fixture = fixture();
        when(fixture.reconcile.countCurrentWithDiff(any())).thenReturn(5L);

        new ReserveXHealthGauges(fixture.registry, fixture.stuck, fixture.deadLetters,
                fixture.reconcile, fixture.redis, fixture.props, fixture.time);
        for (int i = 0; i < 5; i++) {
            assertEquals(5d, gauge(fixture, ReserveXHealthGauges.RECONCILE_DIFF));
        }

        // countCurrentWithDiff 带相关子查询,reconcile_log 又是只增流水;
        // 让 15s 一次的抓取直连 DB 等于给自己加固定负载。
        verify(fixture.reconcile, times(1)).countCurrentWithDiff(any());
    }

    @Test
    void everyGaugeIsRegisteredWithHelpTextUnderTheReservexNamespace() {
        Fixture fixture = fixture();
        when(fixture.stuck.selectCount(any())).thenReturn(0L);
        when(fixture.deadLetters.selectCount(any())).thenReturn(0L);

        new ReserveXHealthGauges(fixture.registry, fixture.stuck, fixture.deadLetters,
                fixture.reconcile, fixture.redis, fixture.props, fixture.time);

        assertEquals(5, fixture.registry.getMeters().size());
        for (String name : new String[]{
                ReserveXHealthGauges.STUCK_PENDING,
                ReserveXHealthGauges.STUCK_OVERDUE,
                ReserveXHealthGauges.RECONCILE_DIFF,
                ReserveXHealthGauges.DEADLETTER_PENDING,
                ReserveXHealthGauges.PENDING_PERSIST_BACKLOG}) {
            var meter = fixture.registry.find(name).gauge();
            assertNotNull(meter, name + " 未注册");
            assertNotNull(meter.getId().getDescription(), name + " 缺 description");
        }
    }

    private static double gauge(Fixture fixture, String name) {
        return fixture.registry.get(name).gauge().value();
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<LambdaQueryWrapper<T>> captor() {
        return (ArgumentCaptor<LambdaQueryWrapper<T>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }

    private static Fixture fixture() {
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        DeadLetterMessageMapper deadLetters = mock(DeadLetterMessageMapper.class);
        ReconcileLogMapper reconcile = mock(ReconcileLogMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(NOW);
        when(time.today()).thenReturn(NOW.toLocalDate());
        when(time.zone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        return new Fixture(new SimpleMeterRegistry(), stuck, deadLetters, reconcile,
                redis, zset, new ReserveXProperties(), time);
    }

    private record Fixture(SimpleMeterRegistry registry,
                           StuckReservationMapper stuck,
                           DeadLetterMessageMapper deadLetters,
                           ReconcileLogMapper reconcile,
                           StringRedisTemplate redis,
                           ZSetOperations<String, String> zset,
                           ReserveXProperties props,
                           TimeSupport time) {
    }
}
