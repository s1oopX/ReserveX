package com.reservex.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code reservex.*} 的类型安全绑定 —— **业务配置的唯一读取入口**。
 *
 * <p>纪律:代码里禁止出现 {@code @Value("${reservex.xxx}")} 散读。
 * 全部走这个类,理由有二:
 * <ol>
 *   <li>散读的配置项**拼错了不报错**(注入 null 或默认值),这正是 M7「配了不生效」那类静默错;</li>
 *   <li>集中绑定后,{@code bootstrap/} 的启动断言才有一个统一的检查面。</li>
 * </ol>
 *
 * <p>⚠️ 时区只从 {@link #getZoneId()} 取。全项目禁止 {@code ZoneId.systemDefault()} ——
 * 它读的是容器 TZ,漏配就静默偏 8h 且无任何报错(08 §7.2)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "reservex")
public class ReserveXProperties {

    /** 业务时区单一真理源。所有"当日结束/次日零点/valid_until/dup_ttl/QR exp"都从这里取。 */
    private String zone = "Asia/Shanghai";

    private Map<String, DataSourceProps> datasource = new LinkedHashMap<>();
    private Quota quota = new Quota();
    private Slot slot = new Slot();
    private RedisKey redisKey = new RedisKey();
    private Pending pending = new Pending();
    private Reconcile reconcile = new Reconcile();
    private Reminder reminder = new Reminder();
    private Aes aes = new Aes();
    private IdHash idHash = new IdHash();
    private Qr qr = new Qr();
    private AdminBootstrap adminBootstrap = new AdminBootstrap();
    private RateLimit ratelimit = new RateLimit();
    private Risk risk = new Risk();
    private Executor executor = new Executor();
    private Consumer consumer = new Consumer();
    private Id id = new Id();

    /** 解析后的时区。全项目取 {@code ZoneId} 只走这一个方法。 */
    public ZoneId getZoneId() {
        return ZoneId.of(zone);
    }

    // ------------------------------------------------------------------
    @Data
    public static class DataSourceProps {
        private String url;
        private String username;
        private String password;
        private Pool pool = new Pool();

        @Data
        public static class Pool {
            private int maximumPoolSize = 20;
            private int minimumIdle = 5;
            private long connectionTimeout = 3000;
            private long maxLifetime = 1_500_000;
        }
    }

    @Data
    public static class Quota {
        /**
         * v1 **固定 1,不可配**。启动时断言 != 1 直接 fail-fast。
         * 理由:{@code id_card_route} 的 PK 与 Lua 的 {@code SET NX dup} 都只能表达
         * "一天至多一条",配成 2 时两道防线同时失效(03 §3.1)。
         */
        private int dailyPerIdcard = 1;
        private boolean failFastOnInvalid = true;
    }

    @Data
    public static class Slot {
        private String genCron = "0 30 2 * * ?";
        private int genDaysAhead = 1;
    }

    @Data
    public static class RedisKey {
        /** TTL 一律派生不硬编,避免"跨天场次的 key 提前消失"(04 §1.1 根因)。 */
        private int dupTtlCapDays = 7;
        private long metaTtlBaseSec = 86400;
        private long metaTtlJitterSec = 300;
    }

    @Data
    public static class Pending {
        private int persistTimeoutSec = 30;
        private String scanCron = "*/10 * * * * ?";
        private int scanPageSize = 500;
        /** 同一 rno 补投上限;耗尽转 stuck,**不自主 10.2a**(04 §三·补)。 */
        private int reinjectMax = 5;
    }

    @Data
    public static class Reconcile {
        private int pageSize = 500;
        /** 默认只告警不自动改;开启必带 {@code reconcile:fixing:*} 状态守卫(06 §4.5)。 */
        private boolean stockAutoFix = false;
        private Map<String, String> crons = new LinkedHashMap<>();
        /** 守卫:不删在途注册的 route(03 §八·补)。 */
        private int orphanRouteMinAgeMin = 10;
    }

    @Data
    public static class Reminder {
        private int aheadMin = 30;
    }

    @Data
    public static class Aes {
        /** 当前用于**加密**的版本,写入 {@code user.id_card_key_id}。 */
        private String keyId = "aes-v1";
        /**
         * 密钥**集**。解密时按行的 {@code id_card_key_id} 取对应 key —— 没有这个结构,
         * "支持轮换"就是空承诺:换 key 后存量密文永久解不开(03 §2.1)。
         */
        private Map<String, String> keys = new LinkedHashMap<>();
    }

    @Data
    public static class IdHash {
        /**
         * **全局固定 pepper,不是 per-row salt,且不可轮换**。
         * per-row 盐会让同一身份证在两个用户下得到不同 hash → {@code id_card_route}
         * 的全局唯一(M6)直接失效。pepper 进了 route PK,换了等于全表 hash 作废。
         * 这是本项目唯一"照书做反而错"的地方(03 §2.1)。
         */
        private String pepper;
    }

    @Data
    public static class Qr {
        /** 按载荷 kid 取验签密钥；保留旧 key 才能在轮换窗口内验证旧码。 */
        private Map<String, String> keys = new LinkedHashMap<>();
        private String keyId = "qr-v1";
        /** 轮换时旧 kid 保留在此直至窗口过(07 §3.4.1)。 */
        private List<String> acceptedKeyIds = new ArrayList<>(List.of("qr-v1"));
        private int ttlSec = 60;
    }

    @Data
    public static class AdminBootstrap {
        private boolean enabled = true;
        /** 固定 1 → {@code 1 mod 2 = 1} → 行必须在 {@code reservex_ds1}(08 §4.1 坑 1)。 */
        private long userId = 1L;
        private String email = "admin@reservex.local";
        private String initPassword;
        private boolean forceChangeOnFirstLogin = true;
    }

    @Data
    public static class RateLimit {
        private int apiLocalRps = 2000;
        private int userRedisRps = 5;
        private int slotRedisRps = 100;
        private int loginMaxAttempts = 10;
        private int loginIpMaxAttempts = 100;
        private int loginWindowSec = 60;
        private int registerIdentityMaxAttempts = 3;
        private int registerIpMaxAttempts = 20;
        private int registerWindowSec = 3600;
        private int refreshIpMaxAttempts = 120;
        private int refreshWindowSec = 60;
        private int captchaGenerateIpMaxAttempts = 30;
        private int captchaVerifyIpMaxAttempts = 120;
        private int captchaIpWindowSec = 60;
    }

    @Data
    public static class Risk {
        /**
         * 抢号失败(售罄/配额已用)累计达到此阈值 → 给该用户置 captcha-required 标记,
         * 下次抢号必须带验证码。正常用户不会连点 N 次售罄,触发了输一次码即可继续。
         */
        private int captchaThreshold = 5;
        /** captcha-required 标记的存活秒数。 */
        private int captchaRequiredTtlSec = 600;
        /** 风控计数器(risk:user:{userId})的存活秒数(1min 滚动窗口)。 */
        private int riskCounterTtlSec = 60;
        /** 验证码本身的存活秒数。 */
        private int captchaTtlSec = 300;
    }

    @Data
    public static class Executor {
        private PoolSpec mail = new PoolSpec();
        private PoolSpec reconcile = new PoolSpec();
        private Scheduler scheduler = new Scheduler();

        @Data
        public static class PoolSpec {
            private int core = 2;
            private int max = 4;
            private int queue = 200;
            private int keepAliveSec = 60;
            /** {@code caller-runs} | {@code abort} | {@code discard} */
            private String rejected = "caller-runs";
        }

        @Data
        public static class Scheduler {
            /** 默认只有 1 根线程 → 一个任务卡死全部任务停摆(08 §7.4)。 */
            private int poolSize = 8;
        }
    }

    @Data
    public static class Consumer {
        /**
         * ⚠️ 组名是 {@code consumed_event} 的主键前缀:改组名 = 幂等历史全失效,
         * 存量消息会被重复消费。视为**不可变常量**(08 §7.1 红线)。
         */
        private Map<String, String> groups = new LinkedHashMap<>();
        private int maxReconsumeTimes = 16;
        private Map<String, ThreadSpec> thread = new LinkedHashMap<>();
        /** ⚠️ 只允许 1:D6 五阶段是单条语义,批量下一条失败整批重投。 */
        private int consumeMessageBatchMaxSize = 1;

        @Data
        public static class ThreadSpec {
            private int min;
            private int max;
        }
    }

    @Data
    public static class Id {
        /**
         * Snowflake 机器位。优先从环境变量 {@code WORKER_ID} 取;
         * 未设则 Redis {@code INCR reservex:worker-id} mod 32;再失败 fallback 1(单实例 demo)。
         * 多实例用同一 workerId 会生成**重复 ID**(主键冲突 / user_id 撞)。
         */
        private long workerId = 1L;
        private long datacenterId = 1L;
    }
}
