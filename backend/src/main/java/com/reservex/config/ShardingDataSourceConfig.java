package com.reservex.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.reservex.config.ReserveXProperties.DataSourceProps;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.mode.ModeConfiguration;
import org.apache.shardingsphere.infra.config.mode.PersistRepositoryConfiguration;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableReferenceRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 分库数据源:ds0 / ds1 由 ShardingSphere 托管(03 §八)。
 *
 * <p><b>分片规则只覆盖三张表</b>:{@code user}、{@code reservation} 与
 * {@code reservation_transition_outbox},都按 {@code user_id mod 2} 分库。
 * user/reservation 是**绑定表** —— 同 user_id 的两表落同一库,
 * join 命中同库而不产生笛卡尔积。
 *
 * <p>⚠️ <b>单库表一张都不在这里</b>。03 §1.1 明确不用广播表:13 张单库表走
 * {@link SingleDataSourceConfig} 的独立 DataSource。把它们纳进分片规则会让
 * {@code slot_bucket} 之类的库存账变成"两库各一份",对账直接失去意义。
 *
 * <p>⚠️ 本 Bean 是 {@code @Primary} 事务管理器。**单库表的 {@code @Transactional}
 * 必须显式写 {@code transactionManager = "singleTxManager"}** —— 漏写就挂到这里,
 * 现象是"事务提交了但数据不在预期的库",且不报错(08 §7.1 红线)。
 */
@Configuration
@MapperScan(basePackages = "com.reservex.mapper.sharding",
        sqlSessionTemplateRef = "shardingSqlSessionTemplate")
public class ShardingDataSourceConfig {

    /** ShardingSphere 逻辑库名。只在框架内部使用,不对应任何物理 schema。 */
    private static final String LOGIC_DB = "reservex_logic";

    /** 分片算法名,被两张表的分库策略共用。 */
    private static final String ALGO_USER_ID_MOD = "user-id-mod";

    @Bean
    @Primary
    public DataSource shardingDataSource(ReserveXProperties props) throws Exception {
        Map<String, DataSource> actualDataSources = new LinkedHashMap<>();
        actualDataSources.put("ds0", hikari(props.getDatasource().get("ds0"), "ds0"));
        actualDataSources.put("ds1", hikari(props.getDatasource().get("ds1"), "ds1"));

        Properties ssProps = new Properties();
        // 开发期看得见改写后的真实 SQL(落哪个库、改写成什么);prod 由 logging 级别压掉
        ssProps.setProperty("sql-show", "false");

        return ShardingSphereDataSourceFactory.createDataSource(
                LOGIC_DB,
                new ModeConfiguration("Standalone", new PersistRepositoryConfiguration() {
                    @Override
                    public String getType() {
                        return "JDBC";
                    }

                    @Override
                    public Properties getProps() {
                        return new Properties();
                    }
                }),
                actualDataSources,
                List.<RuleConfiguration>of(shardingRule()),
                ssProps);
    }

    /**
     * 分片规则。
     *
     * <p>{@code ds$->{user_id % 2}} 是唯一的分库表达式 —— 与 02-seed.sql 里
     * 「超管 user_id=1 必须落 ds1」是同一个算式的两端。改这里等于改 seed 的落库位置。
     */
    private ShardingRuleConfiguration shardingRule() {
        ShardingRuleConfiguration rule = new ShardingRuleConfiguration();

        rule.getTables().add(shardedTable("user"));
        rule.getTables().add(shardedTable("reservation"));
        rule.getTables().add(shardedTable("reservation_transition_outbox"));

        // 绑定表:同 user_id 的 user 与 reservation 必落同库,join 不跨库
        rule.getBindingTableGroups().add(
                new ShardingTableReferenceRuleConfiguration("user_reservation", "user,reservation"));

        Properties algoProps = new Properties();
        algoProps.setProperty("algorithm-expression", "ds$->{user_id % 2}");
        rule.getShardingAlgorithms().put(ALGO_USER_ID_MOD, new AlgorithmConfiguration("INLINE", algoProps));

        return rule;
    }

    /** 单表的分库规则:只分库不分表(单实例多 schema 的演示形态)。 */
    private ShardingTableRuleConfiguration shardedTable(String logicTable) {
        ShardingTableRuleConfiguration table =
                new ShardingTableRuleConfiguration(logicTable, "ds$->{0..1}." + logicTable);
        table.setDatabaseShardingStrategy(
                new StandardShardingStrategyConfiguration("user_id", ALGO_USER_ID_MOD));
        return table;
    }

    private HikariDataSource hikari(DataSourceProps cfg, String name) {
        if (cfg == null || cfg.getUrl() == null) {
            throw new IllegalStateException(
                    "缺少数据源配置 reservex.datasource." + name + ".url —— 三数据源缺一不可(03 §1.1)");
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("hikari-" + name);
        ds.setJdbcUrl(cfg.getUrl());
        ds.setUsername(cfg.getUsername());
        ds.setPassword(cfg.getPassword());
        ds.setMaximumPoolSize(cfg.getPool().getMaximumPoolSize());
        ds.setMinimumIdle(cfg.getPool().getMinimumIdle());
        ds.setConnectionTimeout(cfg.getPool().getConnectionTimeout());
        ds.setMaxLifetime(cfg.getPool().getMaxLifetime());
        ds.addDataSourceProperty("connectTimeout", cfg.getPool().getJdbcConnectTimeout());
        ds.addDataSourceProperty("socketTimeout", cfg.getPool().getSocketTimeout());
        return ds;
    }

    /**
     * 分库 SqlSessionFactory。
     *
     * <p>⚠️ <b>必须 {@code @Primary}</b>:MyBatis-Plus 的 {@code MybatisPlusAutoConfiguration}
     * 会自动建一个 {@code SqlSessionTemplate},注入单个 {@code SqlSessionFactory}。
     * 本项目有两个工厂(sharding + single),不标 primary 它就分不清用哪个 → 启动直接炸
     * "expected single matching bean but found 2"。
     *
     * <p>标 primary 后,未显式限定的数据访问组件默认走分库工厂;两类 Mapper 仍由
     * 各自的 {@code @MapperScan} 绑定到对应 template。
     */
    @Bean
    @Primary
    public SqlSessionFactory shardingSqlSessionFactory(DataSource shardingDataSource,
                                                        ReserveXProperties props) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(shardingDataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/sharding/*.xml"));
        SqlSessionFactory sessionFactory = factory.getObject();
        sessionFactory.getConfiguration().setDefaultStatementTimeout(
                props.getDatasource().get("ds0").getPool().getStatementTimeoutSec());
        return sessionFactory;
    }

    /**
     * 分库 SqlSessionTemplate(从工厂派生),供 MapperScan 显式绑定。
     */
    @Bean
    @Primary
    public SqlSessionTemplate shardingSqlSessionTemplate(SqlSessionFactory shardingSqlSessionFactory) {
        return new SqlSessionTemplate(shardingSqlSessionFactory);
    }

    /** 分库事务管理器。{@code @Primary} —— 不写 transactionManager 时用的就是它。 */
    @Bean
    @Primary
    public PlatformTransactionManager shardingTxManager(DataSource shardingDataSource) {
        return new DataSourceTransactionManager(shardingDataSource);
    }

    /** 供启动断言核对"分片规则是否只覆盖了预期的表"。 */
    static Collection<String> shardedLogicTables() {
        return List.of("user", "reservation", "reservation_transition_outbox");
    }
}
