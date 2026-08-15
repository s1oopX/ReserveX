package com.reservex.id;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.reservex.config.ReserveXProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 全局唯一 ID(08 §三 / §九)。
 *
 * <p>{@code user_id} / {@code reservation_no} / {@code slot_id} / 各类日志主键都出自这里。
 *
 * <p><b>为什么不用数据库自增</b>:{@code user} 与 {@code reservation} 分库,
 * 两库各自的自增序列会撞;而 {@code user_id} 又是分片键,ID 必须在**写库之前**就确定
 * (要靠它算 {@code mod 2} 决定落哪个库)。
 *
 * <p>⚠️ <b>workerId 不再写死</b>。多实例用同一 workerId 会生成**重复 ID** ——
 * 后果是 {@code reservation_no} 主键冲突(落库失败,还算好),或者更坏:两个不同用户拿到
 * 同一个 {@code user_id}。fallback 链:环境变量 {@code WORKER_ID} →
 * Redis {@code INCR reservex:worker-id} mod 32 → 1(单实例 demo 兜底)。
 * 每层都 log 标注来源。{@code datacenterId} 固定 1(单数据中心)。
 *
 * <p>⚠️ <b>Snowflake 起始值远大于 1</b>,故 seed 里把超管固定成 {@code user_id=1}、
 * 模板固定成 {@code template_id=1..4} 永远不会与运行期生成的 ID 冲突(08 §4.1)。
 */
@Slf4j
@Component
public class IdGenerator {

    /** Snowflake workerId / datacenterId 各 5 位,有效范围 0~31。 */
    private static final long MAX_ID = 31L;
    private static final String REDIS_KEY = "reservex:worker-id";

    private final Environment env;
    private final StringRedisTemplate redis;
    private final ReserveXProperties props;
    private Snowflake snowflake;

    @Autowired
    public IdGenerator(Environment env, StringRedisTemplate redis, ReserveXProperties props) {
        this.env = env;
        this.redis = redis;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        long datacenterId = clamp(props.getId().getDatacenterId());
        long workerId = resolveWorkerId();
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
        log.info("IdGenerator 就绪 workerId={} datacenterId={}", workerId, datacenterId);
    }

    public long nextId() {
        return snowflake.nextId();
    }

    /**
     * fallback 链:env → Redis INCR → props 兜底。
     * 每层都标明来源,Redis 不可用仅影响多实例,单实例用兜底值仍正常。
     */
    private long resolveWorkerId() {
        String envVal = env.getProperty("WORKER_ID");
        if (envVal != null && !envVal.isBlank()) {
            try {
                long parsed = Long.parseLong(envVal.trim());
                if (parsed >= 0 && parsed <= MAX_ID) {
                    log.info("workerId 来自环境变量 WORKER_ID={}", parsed);
                    return parsed;
                }
                log.warn("WORKER_ID={} 越界(允许 0~31),忽略走 Redis 分配", parsed);
            } catch (NumberFormatException e) {
                log.warn("WORKER_ID={} 非整数,忽略走 Redis 分配", envVal);
            }
        }
        try {
            Long incr = redis.opsForValue().increment(REDIS_KEY);
            if (incr != null) {
                long assigned = incr % (MAX_ID + 1);
                log.info("workerId 来自 Redis INCR={} → mod32={}", incr, assigned);
                return assigned;
            }
        } catch (RuntimeException e) {
            log.error("Redis INCR workerId 失败,回退兜底值;多实例下可能 ID 冲突", e);
        }
        long fallback = clamp(props.getId().getWorkerId());
        log.warn("workerId 使用兜底值 {}(单实例 demo 安全,多实例需配 WORKER_ID 或保证 Redis 可用)", fallback);
        return fallback;
    }

    private static long clamp(long v) {
        return Math.max(0L, Math.min(MAX_ID, v));
    }
}
