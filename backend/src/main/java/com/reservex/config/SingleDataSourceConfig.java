package com.reservex.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.reservex.config.ReserveXProperties.DataSourceProps;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 单库数据源:{@code reservex_single} 的 13 张表(03 §1.1)。
 *
 * <p><b>为什么是独立 DataSource 而不是 ShardingSphere 的广播表</b>:
 * 广播表会把每一行**复制到所有分片**。而 {@code slot_bucket} / {@code state_log} /
 * {@code consumed_event} 这些表是**全局唯一真理**,复制两份等于:
 * <ul>
 *   <li>库存账在两库各有一份,对账拿哪份都不对;</li>
 *   <li>{@code id_card_route} 的 PK 唯一性退化成"每库唯一",一人一天一次直接失效;</li>
 *   <li>写放大一倍,而这些表恰是写热点(每次落库都写 route + event + bucket)。</li>
 * </ul>
 *
 * <p>⚠️ <b>用本数据源的 {@code @Transactional} 必须显式指定</b>:
 * <pre>{@code @Transactional(transactionManager = "singleTxManager")}</pre>
 * 漏写会挂到 {@code shardingTxManager}({@code @Primary})上 —— 连接来自分片库,
 * SQL 却打在单库 mapper 上,**事务边界与实际连接不是一回事**,提交/回滚都不生效
 * 且不报错。这是 08 §7.1 的第 2 条红线。
 *
 * <p>⚠️ 跨这两个数据源的操作(注册的"单库写 route + 分库写 user")<b>没有分布式事务</b>,
 * 靠 03 §八·补 的两写顺序 + 失败补偿降低风险,残留孤儿 route 由对账任务告警并人工复核。不要试图用 {@code @Transactional}
 * 包住两者 —— 那只会让人误以为有原子性。
 *
 * <p>⚠️ 所有 {@code DataSource}/{@code SqlSessionFactory} 参数必须带 {@link Qualifier}。
 * Spring 会优先选择分片侧的 {@code @Primary},参数名叫 {@code singleDataSource}
 * 也不会覆盖该规则;漏掉 qualifier 会让单库 SQL 静默走进 ShardingSphere。
 */
@Configuration
@MapperScan(basePackages = "com.reservex.mapper.single",
        sqlSessionTemplateRef = "singleSqlSessionTemplate")
public class SingleDataSourceConfig {

    @Bean
    public DataSource singleDataSource(ReserveXProperties props) {
        DataSourceProps cfg = props.getDatasource().get("single");
        if (cfg == null || cfg.getUrl() == null) {
            throw new IllegalStateException(
                    "缺少数据源配置 reservex.datasource.single.url —— 13 张单库表无处安放(03 §1.1)");
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("hikari-single");
        ds.setJdbcUrl(cfg.getUrl());
        ds.setUsername(cfg.getUsername());
        ds.setPassword(cfg.getPassword());
        // 池最大,因为它承接:落库消费者(20 线程)+ 11 类对账 + 全部路由查询
        ds.setMaximumPoolSize(cfg.getPool().getMaximumPoolSize());
        ds.setMinimumIdle(cfg.getPool().getMinimumIdle());
        ds.setConnectionTimeout(cfg.getPool().getConnectionTimeout());
        ds.setMaxLifetime(cfg.getPool().getMaxLifetime());
        return ds;
    }

    @Bean
    public SqlSessionFactory singleSqlSessionFactory(
            @Qualifier("singleDataSource") DataSource singleDataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(singleDataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/single/*.xml"));
        return factory.getObject();
    }

    /**
     * 单库 SqlSessionTemplate。MapperScan 显式引用它,避免多工厂环境下回退到 primary。
     */
    @Bean
    public SqlSessionTemplate singleSqlSessionTemplate(
            @Qualifier("singleSqlSessionFactory") SqlSessionFactory singleSqlSessionFactory) {
        return new SqlSessionTemplate(singleSqlSessionFactory);
    }

    /**
     * 单库事务管理器。Bean 名 {@code singleTxManager} 是**契约**:
     * 全项目的单库 {@code @Transactional} 都按这个名字引用它,改名等于让所有
     * 显式声明失效(且失效方式是静默挂回 primary)。
     */
    @Bean(name = "singleTxManager")
    public PlatformTransactionManager singleTxManager(
            @Qualifier("singleDataSource") DataSource singleDataSource) {
        return new DataSourceTransactionManager(singleDataSource);
    }
}
