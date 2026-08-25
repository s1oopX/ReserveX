package com.reservex.metrics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.DeadLetterMessage;
import com.reservex.entity.StuckReservation;
import com.reservex.mapper.single.DeadLetterMessageMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.service.ReservationService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 补偿链路的**存量**水位(08 §6.1「一致性」「MQ」两行)。
 *
 * <p>与 {@link ReserveXMetrics} 的分工:计数器数增量,重启归零;gauge 读当下存量。
 * 告警必须建在 gauge 上 —— 「今天没有新增死信」和「死信队列里躺着 300 条」
 * 是两回事,只看计数器会漏掉后者。
 *
 * <p>⚠️ 每个 gauge 都**吞异常返回 NaN**。gauge 在 {@code /actuator/prometheus}
 * 抓取线程上求值,任一个抛出会让整个端点 500 —— DB 抖一下就连带 JVM、HTTP、
 * Hikari 全套指标一起看不见,故障时刚好失去所有视野。NaN 在 Prometheus 里是
 * 「未知」,不会被 {@code > 0} 类规则误判成告警;DB 本身不可用另有
 * Hikari 指标和 {@code up} 兜着。
 *
 * <p>⚠️ 读 DB 的三个 gauge 走 {@link Cached} 限流。抓取间隔可能是 15s,
 * 而 {@code countCurrentWithDiff} 带相关子查询、{@code reconcile_log} 是只增流水
 * (reconcile-a 每分钟一行),让抓取直连 DB 等于给自己加一个固定负载。
 * 底层数据本身最快也才 1 分钟更一次,缓存 30s 不损失信息。
 */
@Slf4j
@Component
public class ReserveXHealthGauges {

    /** 待研判 + 回滚处理中的卡单(status 0/4),对位 07 §4.1 对账中心 Tab 3。 */
    public static final String STUCK_PENDING = "reservex.stuck.pending";
    /** 卡单超 {@code reservex.pending.stuck-alert-after-min} 仍未处理 —— 人工响应已失效。 */
    public static final String STUCK_OVERDUE = "reservex.stuck.overdue";
    /** 今日仍未收敛的对账差异(task_type × slot 计),08 §6.1「diff > 0 持续告警」。 */
    public static final String RECONCILE_DIFF = "reservex.reconcile.diff.current";
    /** 待重放死信,08 §6.1「死信突增=链路异常核心信号」。 */
    public static final String DEADLETTER_PENDING = "reservex.deadletter.pending";
    /** {@code pending:persist} 里等落库的预约数(Redis 侧积压)。 */
    public static final String PENDING_PERSIST_BACKLOG = "reservex.pending.persist.backlog";

    private static final long CACHE_TTL_MS = 30_000L;

    private final StuckReservationMapper stuckMapper;
    private final DeadLetterMessageMapper deadLetterMapper;
    private final ReconcileLogMapper reconcileMapper;
    private final StringRedisTemplate redis;
    private final ReserveXProperties props;
    private final TimeSupport time;

    public ReserveXHealthGauges(MeterRegistry registry,
                                StuckReservationMapper stuckMapper,
                                DeadLetterMessageMapper deadLetterMapper,
                                ReconcileLogMapper reconcileMapper,
                                StringRedisTemplate redis,
                                ReserveXProperties props,
                                TimeSupport time) {
        this.stuckMapper = stuckMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.reconcileMapper = reconcileMapper;
        this.redis = redis;
        this.props = props;
        this.time = time;

        register(registry, STUCK_PENDING, "待人工研判的卡单数", cached(this::readStuckPending));
        register(registry, STUCK_OVERDUE, "超时未处理的卡单数", cached(this::readStuckOverdue));
        register(registry, RECONCILE_DIFF, "今日未收敛的对账差异数", cached(this::readReconcileDiff));
        register(registry, DEADLETTER_PENDING, "待重放的死信数", cached(this::readDeadLetterPending));
        // Redis 侧是 ZCARD,O(1),不值得再加一层缓存。
        register(registry, PENDING_PERSIST_BACKLOG, "等待落库的预约数", this::readPendingBacklog);
    }

    /**
     * gauge 注册一次、之后由注册表在抓取时回调,所以传 supplier 而非快照值。
     *
     * <p>⚠️ {@code strongReference} 必须开:{@code Gauge.builder} 默认对状态对象
     * (这里就是 lambda 本身)持**弱引用**,而除注册表外没人引用这些 lambda,
     * 一次 GC 后指标会静默变成 NaN 并从抓取结果里消失。这种 bug 单测抓不到,
     * 只在生产表现为「这条线跑一阵就没了」。
     */
    private static void register(MeterRegistry registry, String name, String description,
                                Supplier<Number> reader) {
        Gauge.builder(name, reader)
                .description(description)
                .strongReference(true)
                .register(registry);
    }

    private static Supplier<Number> cached(LongSupplier reader) {
        Cached cache = new Cached(reader);
        return cache::get;
    }

    private long readStuckPending() {
        return unboxed(stuckMapper.selectCount(new LambdaQueryWrapper<StuckReservation>()
                .in(StuckReservation::getStatus, 0, 4)));
    }

    private long readStuckOverdue() {
        LocalDateTime cutoff = time.now()
                .minusMinutes(props.getPending().getStuckAlertAfterMin());
        return unboxed(stuckMapper.selectCount(new LambdaQueryWrapper<StuckReservation>()
                .in(StuckReservation::getStatus, 0, 4)
                .lt(StuckReservation::getCreateAt, cutoff)));
    }

    private long readReconcileDiff() {
        return reconcileMapper.countCurrentWithDiff(time.today());
    }

    private long readDeadLetterPending() {
        return unboxed(deadLetterMapper.selectCount(new LambdaQueryWrapper<DeadLetterMessage>()
                .in(DeadLetterMessage::getStatus, 0, 2)));
    }

    /** {@code selectCount} 返回装箱 {@code Long};裸拆箱遇 null 会 NPE 成静默 NaN。 */
    private static long unboxed(Long count) {
        return count == null ? 0L : count;
    }

    private Number readPendingBacklog() {
        try {
            Long size = redis.opsForZSet().zCard(ReservationService.PENDING_KEY);
            return size == null ? 0L : size;
        } catch (RuntimeException e) {
            log.warn("读取 pending 积压指标失败", e);
            return Double.NaN;
        }
    }

    /**
     * 单值 TTL 缓存。读失败缓存 NaN,不缓存旧值 ——
     * 拿 30s 前的数字冒充现在会让告警在故障期间显示「一切正常」。
     */
    private static final class Cached {

        private final LongSupplier reader;
        private final AtomicLong readAtNanos = new AtomicLong(Long.MIN_VALUE);
        private volatile double value = Double.NaN;

        private Cached(LongSupplier reader) {
            this.reader = reader;
        }

        private double get() {
            long now = System.nanoTime();
            long last = readAtNanos.get();
            if (last != Long.MIN_VALUE && now - last < CACHE_TTL_MS * 1_000_000L) {
                return value;
            }
            // 抓取端多副本时可能并发进来;重复读一次 count 无副作用,不值得上锁。
            try {
                value = reader.getAsLong();
            } catch (RuntimeException e) {
                value = Double.NaN;
                log.warn("读取补偿链路水位指标失败", e);
            }
            readAtNanos.set(now);
            return value;
        }
    }
}
