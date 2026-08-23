package com.reservex.config;

import com.reservex.config.ReserveXProperties.DataSourceProps;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SingleDataSourceConfigTest {

    @Test
    void singleFactoryAndTransactionManagerIgnorePrimaryDataSource() {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            DataSource single = context.getBean("singleDataSource", DataSource.class);
            SqlSessionFactory factory = context.getBean("singleSqlSessionFactory", SqlSessionFactory.class);
            DataSourceTransactionManager tx = context.getBean("singleTxManager", DataSourceTransactionManager.class);

            assertSame(single, factory.getConfiguration().getEnvironment().getDataSource());
            assertSame(single, tx.getDataSource());
            assertEquals(5, factory.getConfiguration().getDefaultStatementTimeout());
            var hikari = (HikariDataSource) single;
            assertEquals(3000, hikari.getDataSourceProperties().get("connectTimeout"));
            assertEquals(5000, hikari.getDataSourceProperties().get("socketTimeout"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SingleDataSourceConfig.class)
    static class TestConfig {

        @Bean
        ReserveXProperties reserveXProperties() {
            DataSourceProps single = new DataSourceProps();
            single.setUrl("jdbc:mysql://unused/reservex_single");
            single.setUsername("unused");
            single.setPassword("unused");

            ReserveXProperties props = new ReserveXProperties();
            props.getDatasource().put("single", single);
            return props;
        }

        @Bean
        @Primary
        DataSource shardingDataSource() {
            return new DriverManagerDataSource();
        }
    }
}
