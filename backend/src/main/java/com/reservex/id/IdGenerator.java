package com.reservex.id;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
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
 * <p>⚠️ <b>demo 单实例把 workerId 写死为 1。</b>多实例部署时两个进程用同一个 workerId
 * 会生成**重复 ID** —— 后果是 {@code reservation_no} 主键冲突(落库失败,还算好),
 * 或者更坏:两个不同用户拿到同一个 {@code user_id}。v2 的做法见 08 §九
 * (从 Redis {@code INCR} 或环境变量取 workerId)。这条是认下的降级点,不是遗漏。
 *
 * <p>⚠️ <b>Snowflake 起始值远大于 1</b>,故 seed 里把超管固定成 {@code user_id=1}、
 * 模板固定成 {@code template_id=1..4} 永远不会与运行期生成的 ID 冲突(08 §4.1)。
 */
@Component
public class IdGenerator {

    /** demo 单实例:数据中心与机器位都写死。多实例见类注释。 */
    private static final long WORKER_ID = 1L;
    private static final long DATACENTER_ID = 1L;

    private final Snowflake snowflake = IdUtil.getSnowflake(WORKER_ID, DATACENTER_ID);

    public long nextId() {
        return snowflake.nextId();
    }
}
