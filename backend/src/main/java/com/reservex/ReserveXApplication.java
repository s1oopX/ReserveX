package com.reservex;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ReserveX 启动类 —— 单进程模块化单体(08 §二 D8)。
 *
 * <p>一个 jar 里同时跑三个逻辑模块,靠包边界隔离,v2 拆独立进程只改部署不改代码:
 * <ul>
 *   <li>{@code reservex-api}        —— controller/service:抢号、核销、查询(含限流 + Lua 调用)</li>
 *   <li>{@code reservex-worker}     —— mq/:MQ 消费 + 延时任务 + 提醒 + 异步落库</li>
 *   <li>{@code reservex-reconciler} —— reconcile/scanner:发布补偿 + 状态扫描 + 一致性对账</li>
 * </ul>
 *
 * <p>⚠️ 排除 {@link DataSourceAutoConfiguration}:本项目有三个数据源
 * (ds0/ds1 走 ShardingSphere,single 独立),自动装配会试图建一个默认 DataSource
 * 并让 {@code @Transactional} 悄悄挂上去 —— 那正是 03 §1.1 要防的"单库表误挂分片库"。
 *
 * <p>⚠️ Mapper 物理分包扫描:{@code mapper.sharding} 与 {@code mapper.single} 在
 * {@code config/DataSourceConfig} 里分别绑定各自的 SqlSessionFactory,此处不做统一
 * {@code @MapperScan} 全包扫描,否则两类 mapper 会挂到同一个工厂上。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties
public class ReserveXApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReserveXApplication.class, args);
    }
}
