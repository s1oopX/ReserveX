package com.reservex.bootstrap;

import com.reservex.config.ReserveXProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 配置红线的启动断言(08 §7.1)。
 *
 * <p>收的都是**同一类缺陷**:配置值改错了,系统照样起得来、功能测试照样绿,
 * 但某条不变量已经悄悄失效。这类东西只有在启动时拒绝才拦得住。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigGuard {

    private final ReserveXProperties props;

    /** MySQL 默认 {@code max_connections=151};三池之和必须留出余量给 CLI 与对账。 */
    private static final int MYSQL_MAX_CONNECTIONS_ASSUMED = 151;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    @PostConstruct
    public void assertConfig() {
        assertQuota();
        assertRedisDatabase();
        assertConnectionBudget();
        assertConsumerSemantics();
        assertSnowflakeIdRange();
        log.info("配置红线断言通过");
    }

    /**
     * 配额只允许 1(03 §3.1 / M7 裁定)。
     *
     * <p>{@code id_card_route} 的 PK {@code (id_card_hash, slot_date)} 与 Lua 的
     * {@code SET NX dup} 都只能表达"一天至多一条"。配成 2 时**两道防线同时失效**:
     * Lua 第一道直接拒掉第二次(用户根本约不到),若强行放开 dup 则第二次必然撞 route PK
     * → 走 10.2a 回滚 → 用户看到"抢号成功后又失败"。
     *
     * <p>保留配置项而非删掉,是因为运维会先看到明确报错;删掉则改配置的人不知道自己在改一个不存在的东西。
     */
    private void assertQuota() {
        int daily = props.getQuota().getDailyPerIdcard();
        if (props.getQuota().isFailFastOnInvalid() && daily != 1) {
            throw new IllegalStateException(
                    "reservex.quota.daily-per-idcard=" + daily + ",但 v1 结构上只支持 1。"
                            + "route PK 与 Lua SET NX 都是「一天一条」的语义,改这个值会让两道防线同时失效。"
                            + "真要支持 N 次/天需同时改 PK 语义与 Lua 判重方式,是 v2 演进项(03 §3.1)");
        }
    }

    /**
     * Redis 只允许 db 0(08 §4.6)。
     *
     * <p>分 db 得不到任何隔离:{@code maxmemory-policy} 是**实例级**,
     * {@code used_memory}/{@code --bigkeys}/{@code SLOWLOG} 也都不分 db。
     * 代价却是实打实的:限流 key 若在另一个 db,就不能与抢号扣减合进同一个 Lua
     * → D4-A 的 2 round-trip 变 3。隔离靠 key 前缀命名空间,真要隔离靠独立实例。
     */
    private void assertRedisDatabase() {
        if (redisDatabase != 0) {
            throw new IllegalStateException(
                    "spring.data.redis.database=" + redisDatabase + ",只允许 0。"
                            + "分 db 不改变 maxmemory-policy(实例级),只会把抢号的 2 次 round-trip 变 3 次;"
                            + "隔离靠 key 前缀,真要资源隔离用独立实例(08 §4.6)");
        }
    }

    /**
     * 连接预算(08 §7.4)。
     *
     * <p>两条约束:
     * <ol>
     *   <li>三个 Hikari 池之和 &lt; MySQL {@code max_connections},否则峰值时新连接被拒,
     *       现象是"随机接口报连不上库"而不是"池满等待";</li>
     *   <li>{@code persistence} 消费线程数 ≤ single 池 —— 它是唯一的写库消费者,
     *       线程数就是 DB 写并发的上界。线程比连接多则线程在等连接,把 MQ 消费伪装成"DB 慢"。</li>
     * </ol>
     */
    private void assertConnectionBudget() {
        int sum = props.getDatasource().values().stream()
                .mapToInt(ds -> ds.getPool().getMaximumPoolSize())
                .sum();
        if (sum >= MYSQL_MAX_CONNECTIONS_ASSUMED) {
            throw new IllegalStateException(
                    "三个 Hikari 池之和 " + sum + " 已达 MySQL max_connections("
                            + MYSQL_MAX_CONNECTIONS_ASSUMED + ")。必须留余量给 CLI 与对账(08 §7.4)");
        }

        ReserveXProperties.DataSourceProps single = props.getDatasource().get("single");
        ReserveXProperties.Consumer.ThreadSpec persistence = props.getConsumer().getThread().get("persistence");
        if (single != null && persistence != null
                && persistence.getMax() > single.getPool().getMaximumPoolSize()) {
            throw new IllegalStateException(
                    "persistence 消费线程 " + persistence.getMax() + " > single 池 "
                            + single.getPool().getMaximumPoolSize()
                            + "。它是唯一的写库消费者,线程数就是 DB 写并发上界;"
                            + "线程比连接多只会让线程排队等连接,把 MQ 消费伪装成「DB 慢」(08 §7.4)");
        }
    }

    /**
     * 批量消费必须为 1(08 §7.4)。
     *
     * <p>D6 落库消费者是**五阶段单条**语义。批量下一条失败会把**整批**重投,
     * 已经成功的条目被重复执行 —— 而它们的幂等保护是按单条 {@code event_id} 建的,
     * 重复执行的是"另一条消息",幂等表拦不住。
     */
    private void assertConsumerSemantics() {
        int batch = props.getConsumer().getConsumeMessageBatchMaxSize();
        if (batch != 1) {
            throw new IllegalStateException(
                    "reservex.consumer.consume-message-batch-max-size=" + batch + ",只允许 1。"
                            + "D6 五阶段是单条语义,批量下一条失败会整批重投,已成功的条目被重复执行(08 §7.4)");
        }
    }

    /**
     * Snowflake workerId / datacenterId 各 5 位,有效范围 0~31。
     * 越界会让 Hutool Snowflake 构造抛异常,但兜底值配错时(IdGenerator 回退到 props)
     * 需要更早、更明确的报错。
     */
    private void assertSnowflakeIdRange() {
        long worker = props.getId().getWorkerId();
        long dc = props.getId().getDatacenterId();
        if (worker < 0 || worker > 31 || dc < 0 || dc > 31) {
            throw new IllegalStateException(
                    "reservex.id.worker-id=" + worker + ", datacenter-id=" + dc
                            + "。Snowflake 机器/数据中心位各 5 位,允许 0~31;"
                            + "多实例需设环境变量 WORKER_ID 或保证 Redis 可用(08 §九)");
        }
    }
}
