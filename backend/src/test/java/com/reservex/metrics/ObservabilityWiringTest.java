package com.reservex.metrics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把埋点(Java)和告警规则(YAML)之间那条**没有编译器保护**的缝隙钉住。
 *
 * <p>为什么需要它:PromQL 引用一个不存在的指标名不会报错,只会**永远不触发**。
 * 于是「埋点改名」「规则漏配」「抓取目标写错」的现象都是同一个 —— 告警很安静,
 * 与「一切正常」无法区分。这正是本轮要消灭的失效模式,不能靠人记得同步两边。
 *
 * <p>本类只做静态文本核对(与 {@link com.reservex.config.RocketMqBrokerConfigTest}
 * 同一套路)。真正的 {@code promtool check rules} / 活体抓取仍需起容器,
 * 那部分不在单测覆盖范围内。
 */
class ObservabilityWiringTest {

    private static final Path RULES = repoFile("docker", "prometheus", "rules", "reservex.yml");
    private static final Path PROM = repoFile("docker", "prometheus", "prometheus.yml");
    private static final Path ALERTMANAGER =
            repoFile("docker", "alertmanager", "alertmanager.tmpl.yml");
    private static final Path COMPOSE = repoFile("docker-compose.yml");

    /**
     * 规则里出现的每个 {@code reservex_*} 序列名,都必须能从常量反推出来。
     * 改了 Java 常量却忘了改规则,这里会红。
     */
    @Test
    void everyReserveXSeriesInTheRulesComesFromAConstant() throws IOException {
        List<String> declared = declaredSeries();

        Matcher found = Pattern.compile("\\breservex_[a-z0-9_]+").matcher(Files.readString(RULES));
        while (found.find()) {
            String series = found.group();
            // Counter 在 Prometheus 侧会被加上 _total 后缀,规则里写的是加了后缀的名字。
            String base = series.endsWith("_total")
                    ? series.substring(0, series.length() - "_total".length())
                    : series;
            assertThat(declared.contains(series) || declared.contains(base))
                    .as("规则引用了 %s,但它不来自任何埋点常量 —— 这条规则永远不会触发", series)
                    .isTrue();
        }
    }

    /**
     * 每条业务规则都要有 absent() 兄弟条兜底。少一条,对应指标消失时就没人知道。
     */
    @Test
    void everyDataSourceHasAnAbsentCompanion() throws IOException {
        String rules = Files.readString(RULES);

        assertThat(rules).contains("absent(reservex_reconcile_diff_current)");
        assertThat(rules).contains("absent(redis_memory_used_bytes)");
        assertThat(rules).contains("absent(rocketmq_consumer_lag_messages)");
        assertThat(rules).contains("absent(hikaricp_connections_active)");
        assertThat(rules).contains("absent(executor_pool_core_threads{name=\"taskScheduler\"})");
        // 日志链路这两个指标都是组件构造时注册的 Gauge,健康系统上必然存在 ——
        // 所以它们能用 absent() 兜底(写入侧的 Counter 不能,见下面那条测试)。
        assertThat(rules).contains("absent(loki_source_file_files_active_total)");
        assertThat(rules).contains(
                "absent(loki_compactor_apply_retention_last_successful_run_timestamp_seconds)");
        // 抓取端自己掉线是最上游的因,必须 critical。
        assertThat(rules).containsPattern("(?s)alert: MonitoringTargetDown.*?severity: critical");
    }

    /**
     * 目标掉线的两条规则必须是**互补**的:critical 白名单用 {@code =~},
     * warning 用 {@code !~},且两者的 job 列表逐字相同。
     *
     * <p>为什么要钉死:原本是一条 {@code up == 0} 全覆盖 critical。加入
     * loki/alloy/grafana 之后,Grafana 重启就会半夜叫人,所以拆成了两级。
     * 但白名单有个反面风险 —— 新加的抓取目标若忘了进列表,就会**谁也不管**。
     * 互补正则保证任何 job 必然落进恰好一条,包括将来新增的。
     */
    @Test
    void targetDownRulesAreExactComplementsSoNoJobGoesUnwatched() throws IOException {
        String rules = activeConfig(RULES);

        String critical = captureOne(rules, "up\\{job=~\"([^\"]+)\"} == 0");
        String warning = captureOne(rules, "up\\{job!~\"([^\"]+)\"} == 0");
        assertThat(critical)
                .as("两条规则的 job 列表必须逐字相同,否则会出现空洞或重复告警")
                .isEqualTo(warning);

        List<String> jobs = new ArrayList<>();
        Matcher job = Pattern.compile("job_name: (\\S+)").matcher(Files.readString(PROM));
        while (job.find()) {
            jobs.add(job.group(1));
        }
        assertThat(jobs).contains("backend", "loki", "alloy", "grafana");

        // Prometheus 的标签正则是全锚定的,这里照同样语义编译。
        Pattern whitelisted = Pattern.compile("^(?:" + critical + ")$");
        List<String> alertingChain =
                List.of("backend", "redis", "rocketmq", "prometheus", "alertmanager");

        // 现有目标 + 一个假想的将来目标:落进 critical 的必须恰好是告警链路本身,
        // 其余(含将来新加的)一律由 warning 那条接住,不存在无人管的目标。
        Stream.concat(jobs.stream(), Stream.of("some-future-exporter")).forEach(name ->
                assertThat(whitelisted.matcher(name).matches())
                        .as("job %s 的归属:critical 白名单当且仅当它属于告警链路", name)
                        .isEqualTo(alertingChain.contains(name)));
    }

    /**
     * 写入侧的 Counter **不能**配 absent(),也不能按「日志量掉到 0」告警。
     *
     * <p>alloy 只在首次写入某 tenant 时才把 {@code loki_write_*} 初始化为 0
     * (上游 shards.go 的 initBatchMetrics);Loki 侧的 discarded 也是首次丢弃
     * 才出现。而本项目所有 log 都是有条件打印的 —— 对账无差异、无卡单时一行不写,
     * 夜间空转十几个小时是**正常**状态。给这些序列配 absent() 或按量报警
     * 等于每晚误报一次,而每晚误报的告警三天后就会被所有人无视。
     */
    @Test
    void writeSideCountersHaveNoAbsentCompanionBecauseIdleIsNormal() throws IOException {
        String rules = activeConfig(RULES);

        assertThat(rules).doesNotContain("absent(loki_write_dropped_entries_total");
        assertThat(rules).doesNotContain("absent(loki_write_sent_entries_total");
        assertThat(rules).doesNotContain("absent(loki_discarded_samples_total");
        assertThat(rules)
                .as("空转时日志量必然为 0,按量报警等于每晚误报")
                .doesNotContain("rate(loki_write_sent_entries_total");

        // 代替方案:看采集端「在不在盯着文件」,它与业务日志量无关,空转时依然准确。
        assertThat(rules).containsPattern(
                "(?s)alert: LogPipelineNotTailing.*?loki_source_file_files_active_total == 0");
    }

    /**
     * 保留策略必须盯 {@code apply_retention} 那个时间戳,不能盯 {@code compact_tables}。
     *
     * <p>上游 {@code tables_manager.go} 里两者由
     * {@code runCompaction(applyRetention=true/false)} 分别更新 —— 压缩在跑
     * 不代表保留在跑。而保留停了 chunk 就永远不删,盘会一直涨到写满,
     * 连带把 MySQL/Redis 一起带走,后果远大于「少了几天日志」。
     */
    @Test
    void retentionAlertWatchesRetentionNotMerelyCompaction() throws IOException {
        String rules = activeConfig(RULES);

        assertThat(rules)
                .contains("loki_compactor_apply_retention_last_successful_run_timestamp_seconds");
        assertThat(rules)
                .as("compact_tables 的时间戳只说明压缩在跑,不代表保留在跑")
                .doesNotContain("loki_boltdb_shipper_compact_tables_operation_last_successful_run");
    }

    /**
     * 两个实测踩过的坑:标签值是 bean 名,分母必须是 core 而不是 max。
     *
     * <p>{@code ThreadPoolTaskScheduler} 底层是 {@code ScheduledThreadPoolExecutor},
     * 只用核心线程,{@code maximumPoolSize} 恒为 {@code Integer.MAX_VALUE}
     * (实测导出值 2147483647)。拿 max 当分母这条规则就永远不触发。
     */
    @Test
    void scheduledPoolRuleComparesAgainstCoreThreadsOfTheNamedBean() throws IOException {
        String rules = activeConfig(RULES);

        assertThat(rules).contains("executor_active_threads{name=\"taskScheduler\"}");
        assertThat(rules).contains("executor_pool_core_threads{name=\"taskScheduler\"}");
        assertThat(rules)
                .as("拿 executor_pool_max_threads 当分母 = 规则永远不触发(实测 2147483647)")
                .doesNotContain("executor_pool_max_threads");
        assertThat(rules)
                .as("Micrometer 的 name 标签取 bean 名 taskScheduler,不是 scheduling")
                .doesNotContain("name=\"scheduling\"");
    }

    /**
     * Redis 内存占比是 noeviction 下唯一的降级前兆,除法必须先挡住 maxmemory=0。
     * 未设 maxmemory 时该指标为 0,不挡就是 0/0 = NaN,规则静默失效。
     */
    @Test
    void redisMemoryRuleGuardsAgainstZeroMaxMemory() throws IOException {
        assertThat(Files.readString(RULES))
                .containsPattern("(?s)alert: RedisMemoryHigh.*?redis_memory_max_bytes > 0\\s*\\n\\s*and");
    }

    /**
     * 抓取必须在容器网内直连 backend:8080,不能经 Caddy —— Caddy 挡着 /actuator/*
     * (08 §五 红线 #35),走它抓只会一直 403/404。
     */
    @Test
    void backendIsScrapedInNetworkNotThroughCaddy() throws IOException {
        String prom = Files.readString(PROM);

        assertThat(prom).contains("metrics_path: /actuator/prometheus");
        assertThat(prom).contains("targets: [\"backend:8080\"]");
        assertThat(prom).doesNotContain("caddy");
        // 抓取端和发信端自己哑掉也要能被 up == 0 覆盖。
        assertThat(prom).contains("job_name: alertmanager");
        assertThat(prom).contains("job_name: prometheus");
    }

    /**
     * Alertmanager **不展开**配置里的环境变量。写 ${VAR} 会被当成字面量收件人,
     * 表现是发信失败且原因难查,所以模板里只能出现 __XXX__ 占位符。
     */
    @Test
    void alertmanagerTemplateAvoidsEnvVarInterpolationAndInlinePassword() throws IOException {
        String template = activeConfig(ALERTMANAGER);

        assertThat(template)
                .as("Alertmanager 不展开 ${VAR},会把它当字面量")
                .doesNotContain("${");
        assertThat(template).contains("__ALERT_EMAIL_TO__");
        assertThat(template).contains("__SMTP_FROM__");
        // 口令只能走文件,内联等于把 QQ 授权码提交进仓库。
        assertThat(template).contains("smtp_auth_password_file: /run/secrets/smtp_password");
        assertThat(template).doesNotContainPattern("(?m)^\\s*smtp_auth_password:");

        String entrypoint = Files.readString(
                repoFile("docker", "alertmanager", "entrypoint.sh"));
        assertThat(entrypoint).contains("${ALERT_EMAIL_TO:?");
        assertThat(entrypoint).contains("${SMTP_FROM:?");
    }

    /**
     * 模拟 entrypoint 的渲染 + 残留占位符守卫。
     *
     * <p>上一个测试只分别检查"模板里有占位符"和"entrypoint 校验了变量",两条都绿,
     * 但**没有一条走过渲染本身** —— 于是漏掉了这个:守卫的 {@code grep '__[A-Z_]*__'}
     * 会命中模板顶部注释里用来解释占位符的那段 {@code __XXX__} 散文,
     * 判定"仍有未替换的占位符"并 {@code exit 1}。结果是容器启动即退出、无限重启,
     * Alertmanager 从头到尾没起来过 = **告警一封都发不出**,而 compose ps 只显示 restarting。
     *
     * <p>所以这里断言的是渲染后的**有效配置**(剥掉注释)不含占位符,
     * 与 entrypoint 现在的判据同款。
     */
    @Test
    void renderedAlertmanagerConfigLeavesNoPlaceholderInEffectiveLines() throws IOException {
        String rendered = Files.readString(ALERTMANAGER)
                .replace("__SMTP_FROM__", "ops@example.com")
                .replace("__ALERT_EMAIL_TO__", "oncall@example.com");

        String effective = rendered.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));

        assertThat(effective)
                .as("渲染后有效配置仍有占位符 → entrypoint 守卫会 exit 1,容器无限重启")
                .doesNotContainPattern("__[A-Z_]+__");
        assertThat(effective).contains("ops@example.com").contains("oncall@example.com");

        // 守卫必须剥注释后再查,否则模板里解释占位符的散文会把自己判死。
        String entrypoint = Files.readString(
                repoFile("docker", "alertmanager", "entrypoint.sh"));
        assertThat(entrypoint)
                .as("残留占位符守卫必须先过滤注释行")
                .containsPattern("grep -v '\\^\\[\\[:space:\\]\\]\\*#'");
    }

    /**
     * 曾经写过又删掉的坑:压 {@code domain="compensation"} 的抑制规则。
     *
     * <p>{@code equal} 只能对齐 {@code env},而所有序列的 env 都一样,
     * 那条规则的真实语义是「任何一个抓取目标掉线 → 全部补偿告警静音」——
     * redis-exporter 挂掉就足以吞掉真实的死信告警。
     */
    @Test
    void noInhibitRuleSilencesTheWholeCompensationDomain() throws IOException {
        assertThat(activeConfig(ALERTMANAGER))
                .doesNotContainPattern("(?m)^\\s*target_matchers:\\s*\\['domain=\"compensation\"'\\]");
    }

    /**
     * Alertmanager 必须能出网发 SMTP:{@code data} 网是 {@code internal: true},
     * 只挂它会让告警永远发不出去,而且现象是静默失败。
     */
    @Test
    void alertmanagerCanReachTheInternetForSmtp() throws IOException {
        String compose = Files.readString(COMPOSE);

        assertThat(compose).containsPattern(
                "(?s)alertmanager:.*?networks: \\[edge, data\\]");
        assertThat(compose).containsPattern("(?s)secrets:\\s*\\n\\s*#.*?smtp_password:\\s*\\n\\s*environment: QQ_SMTP_PASSWORD");
        // 无鉴权的监控端口不能对外:只绑回环(同 caddy 的做法)。
        assertThat(compose).contains("ports: [\"127.0.0.1:9090:9090\"]");
        assertThat(compose).contains("ports: [\"127.0.0.1:9093:9093\"]");
        // broker 的 Prometheus 端口要在网内可见,否则那条积压规则没有数据源。
        assertThat(compose).contains("expose: [\"10909\", \"10911\", \"5557\"]");
    }

    /**
     * 日志采集的三处只能全对、错一处就静默失效的接线。
     *
     * <p>三处分别是:采集路径必须是**精确文件名**、日志卷必须是同一个命名卷、
     * 以及镜像里必须先建好 {@code /app/logs} 并归 app。
     */
    @Test
    void logShippingPathVolumeAndOwnershipLineUp() throws IOException {
        String alloy = Files.readString(repoFile("docker", "alloy", "config.alloy"));
        String prodYml = Files.readString(
                repoFile("backend", "src", "main", "resources", "application-prod.yml"));
        String dockerfile = Files.readString(repoFile("backend", "Dockerfile"));
        String compose = Files.readString(COMPOSE);

        // 采集路径必须精确到文件名。logback 的归档是 .gz(reservex.json.2026-08-25.0.gz),
        // 用 *.json* 这类通配会把归档也当日志重读一遍,同一件事在 Loki 里出现两遍 ——
        // 而「同一件事出现两遍」最容易把排障带偏。
        assertThat(alloy).contains("__path__ = \"/logs/reservex.json\"");
        assertThat(alloy).doesNotContain("*.json");

        // 应用写的路径必须正是 alloy 采的那个文件(卷内相对路径一致)。
        assertThat(prodYml).contains("name: /app/logs/reservex.json");
        assertThat(prodYml).contains("file: ecs");

        // 空命名卷挂在镜像中**不存在**的路径上时是 root:root 755,应用以 app 跑就写不进去。
        // 路径先存在,Docker 才会把属主 copy-up 到新卷。
        assertThat(dockerfile).contains("mkdir -p /app/logs && chown app:app /app/logs");
        assertThat(dockerfile).contains("USER app");

        // 两侧必须是同一个命名卷,且 alloy 侧只读。
        assertThat(compose).contains("- backend-logs:/app/logs");
        assertThat(compose).contains("- backend-logs:/logs:ro");
    }

    /**
     * 采集容器不许拿 docker socket,且不以 root 跑。
     *
     * <p>挂 {@code /var/run/docker.sock} 是更常见的采集写法(自动发现所有容器),
     * 但那等于把宿主 root 交给这个容器 —— socket 上能创建特权容器、挂宿主根目录。
     * 为了看日志付这个代价不值当,所以只读挂一个日志卷,覆盖面换安全边界。
     */
    @Test
    void logCollectorHoldsNoDockerSocketAndDropsRoot() throws IOException {
        String compose = activeConfig(COMPOSE);

        assertThat(compose)
                .as("挂 docker.sock 等于把宿主 root 交给采集容器")
                .doesNotContain("/var/run/docker.sock");
        // 镜像建了 alloy 用户(uid 473)却没 USER 它,默认以 root 跑,这里显式降权。
        assertThat(compose).containsPattern("(?s)alloy:.*?user: \"473\"");
    }

    /**
     * Loki 的保留期要真生效,三件事必须同时成立;缺任何一件都是「配了但不删」。
     */
    @Test
    void lokiRetentionNeedsAllThreeSettingsAndA24hIndexPeriod() throws IOException {
        String loki = activeConfig(repoFile("docker", "loki", "loki.yml"));

        // 少了 retention_enabled,索引会被压缩但 chunk 永远不删,盘一直涨到写满。
        assertThat(loki).contains("retention_enabled: true");
        assertThat(loki).contains("retention_period:");
        // 开启保留时 delete_request_store 是必填项(上游),漏了 Loki 起不来。
        assertThat(loki).contains("delete_request_store:");
        // 保留只在索引周期为 24h 时生效,改成别的值会让保留期静默失效。
        assertThat(loki).contains("period: 24h");

        // 收窄 reject_old_samples_max_age 是有害的且方向容易搞反:
        // 故障期间补读上来的旧行会被静默拒收,而那几天的日志正是最该留下的。
        assertThat(loki)
                .as("这个窗口收窄会让故障期间补读的日志被静默拒收")
                .doesNotContain("reject_old_samples_max_age: 24h");
    }

    /**
     * 无鉴权的日志库不能对外,面板不能留默认口令。
     *
     * <p>Loki 是 {@code auth_enabled: false} —— 谁连上 3100 谁就能读全部日志,
     * 而日志里有身份证密文与手机号后四位。Grafana 则更隐蔽:没设口令时它
     * **不会报错**,而是静默回落到 admin/admin,「启动成功」反而是最坏结果。
     */
    @Test
    void lokiIsNotPublishedAndGrafanaRefusesToStartWithoutAPassword() throws IOException {
        String compose = activeConfig(COMPOSE);

        // 只 expose 不 ports:留在 internal 的 data 网内,查询一律经 Grafana。
        assertThat(compose).containsPattern("(?s)loki:.*?expose: \\[\"3100\"]");
        assertThat(compose).doesNotContain("3100:3100");

        assertThat(compose).contains("GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:?");
        assertThat(compose).contains("GF_USERS_ALLOW_SIGN_UP: \"false\"");
        assertThat(compose).contains("GF_AUTH_ANONYMOUS_ENABLED: \"false\"");
        assertThat(compose).contains("ports: [\"127.0.0.1:3000:3000\"]");

        // 模板必须把这个变量列出来,否则照着填的人会漏掉,启动直接失败。
        assertThat(Files.readString(repoFile(".env.example")))
                .contains("GRAFANA_ADMIN_PASSWORD=");
    }

    /**
     * 面板引用的每个 {@code reservex_*} 序列都必须来自埋点常量。
     *
     * <p>面板写错指标名的现象是「图是空的」,和「这项一直为 0,系统很健康」
     * 长得一模一样 —— 与告警规则写错是同一类静默失效,所以同样钉住。
     */
    @Test
    void everyReserveXSeriesOnTheDashboardComesFromAConstant() throws IOException {
        List<String> declared = declaredSeries();
        String dashboard = Files.readString(
                repoFile("docker", "grafana", "dashboards", "reservex-compensation.json"));

        Matcher found = Pattern.compile("\\breservex_[a-z0-9_]+").matcher(dashboard);
        while (found.find()) {
            String series = found.group();
            String base = series.endsWith("_total")
                    ? series.substring(0, series.length() - "_total".length())
                    : series;
            assertThat(declared.contains(series) || declared.contains(base))
                    .as("面板引用了 %s,但它不来自任何埋点常量 —— 这个图会永远是空的", series)
                    .isTrue();
        }
    }

    /**
     * 面板目录不能嵌套进那个可写的命名卷里。
     *
     * <p>{@code /var/lib/grafana} 是 GF_PATHS_DATA(命名卷 grafana-data)。
     * 把只读 bind 嵌套进可写卷内部虽然 Docker 允许,但「同一棵树里一半可写
     * 一半只读」读代码的人看不出来,改卷时极易把面板目录连带弄没。
     */
    @Test
    void dashboardMountIsSiblingOfProvisioningNotNestedInTheDataVolume() throws IOException {
        String provider = activeConfig(
                repoFile("docker", "grafana", "provisioning", "dashboards", "dashboards.yml"));
        String compose = activeConfig(COMPOSE);

        assertThat(provider).contains("path: /etc/grafana/dashboards");
        assertThat(compose).contains(":/etc/grafana/dashboards:ro");
        assertThat(compose)
                .as("面板目录嵌套进 grafana-data 卷内部,拓扑不可读且改卷易误伤")
                .doesNotContain(":/var/lib/grafana/dashboards");
        // 数据卷本身仍然要在,否则 Grafana 的 sqlite 每次重启都归零。
        assertThat(compose).contains("- grafana-data:/var/lib/grafana");
    }

    /**
     * 只留生效配置,丢掉整行注释。
     *
     * <p>那几个「不许出现」的断言必须看配置本身:这些文件的注释里正好写着
     * {@code ${VAR}} 不展开、{@code executor_pool_max_threads} 恒为 2147483647
     * 这类反面教材,连注释一起断言会把说明文字当成违规。
     */
    private static String activeConfig(Path path) throws IOException {
        return Files.readAllLines(path).stream()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'),
                        StringBuilder::append)
                .toString();
    }

    /**
     * 反方向:每个埋点常量都必须至少有一个消费者(规则或面板)。
     *
     * <p>上面那条测的是「规则引用的名字都来自常量」,它挡的是**写错名字**。
     * 这条挡的是另一半:埋了点却没人看。两者都是静默失效 —— 前者图是空的,
     * 后者数字在涨但没有任何人会知道。
     *
     * <p>本轮真踩了这个坑:{@code reservex_compensate_triggered} 有真实调用点
     * ({@code ReservationPersistenceConsumer} 的身份证路由冲突分支),
     * 但规则与面板双零引用 —— 补偿链路里语义最刺眼的那条路径反倒是唯一看不见的。
     */
    @Test
    void everyMetricConstantHasAtLeastOneConsumer() throws IOException {
        String rules = Files.readString(RULES);
        String dashboard = Files.readString(
                repoFile("docker", "grafana", "dashboards", "reservex-compensation.json"));

        for (String series : declaredSeries()) {
            boolean seen = Stream.of(series, series + "_total")
                    .anyMatch(name -> rules.contains(name) || dashboard.contains(name));
            assertThat(seen)
                    .as("%s 埋了点但规则和面板都没引用 —— 数字在涨,没有任何人会知道", series)
                    .isTrue();
        }
    }

    /**
     * 身份证路由冲突的补偿必须 {@code > 0} 就报,不许等业务基线。
     *
     * <p>它与 {@code stuck_intake} 的处理**故意**相反,理由在事件性质:
     * 后者是流量型计数器,没基线时任何阈值都只会误报或永不触发;而路由冲突是
     * 两个身份证的哈希撞进同一主键,设计上几乎不该发生、发生了就有后果
     * (库存已回补但那一笔没成)。稀有 + 有后果,正是 {@code > 0} 该报的那类。
     */
    @Test
    void routeConflictCompensationAlertsOnAnyOccurrence() throws IOException {
        String rules = activeConfig(RULES);

        assertThat(rules).containsPattern(
                "(?s)alert: CompensationTriggered.*?"
                        + "increase\\(reservex_compensate_triggered_total\\[10m]\\) > 0");
        // 阈值型写法(> N)在这里是错的:撞号一次就该有人知道。
        assertThat(rules).doesNotContainPattern(
                "(?s)alert: CompensationTriggered.*?compensate_triggered_total\\[10m]\\) > [1-9]");
    }

    /**
     * 全部埋点常量,已转成 Prometheus 侧的下划线形式。规则与面板共用这一份 ——
     * 两处都必须只引用这里有的名字。
     */
    private static List<String> declaredSeries() {
        return Stream.of(
                        ReserveXMetrics.STUCK_INTAKE,
                        ReserveXMetrics.REINJECT_TOTAL,
                        ReserveXMetrics.DEADLETTER_CAPTURED,
                        ReserveXMetrics.MQ_SEND_FAILED,
                        ReserveXMetrics.COMPENSATE_TRIGGERED,
                        ReserveXHealthGauges.STUCK_PENDING,
                        ReserveXHealthGauges.STUCK_OVERDUE,
                        ReserveXHealthGauges.RECONCILE_DIFF,
                        ReserveXHealthGauges.DEADLETTER_PENDING,
                        ReserveXHealthGauges.PENDING_PERSIST_BACKLOG)
                .map(name -> name.replace('.', '_'))
                .toList();
    }

    /** 取正则第一个捕获组;取不到就让测试红,而不是静默返回 null。 */
    private static String captureOne(String haystack, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(haystack);
        assertThat(matcher.find())
                .as("配置里找不到 %s —— 规则被改写或删除了", regex)
                .isTrue();
        return matcher.group(1);
    }

    private static Path repoFile(String... parts) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current;
            for (String part : parts) {
                candidate = candidate.resolve(part);
            }
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("repository file not found: " + String.join("/", parts));
    }
}
