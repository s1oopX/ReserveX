package com.reservex.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.infra.datasource.pool.props.creator.DataSourcePoolPropertiesCreator;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShardingSphereHikariCompatibilityTest {

    @Test
    void hikariJdbcUrlIsRecognizedAsShardingSphereUrl() {
        try (var dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:mysql://mysql:3306/reservex_ds0");
            dataSource.setUsername("root");

            var properties = DataSourcePoolPropertiesCreator.create(dataSource)
                    .getConnectionPropertySynonyms()
                    .getStandardProperties();

            assertEquals(dataSource.getJdbcUrl(), properties.get("url"));
            assertEquals(dataSource.getUsername(), properties.get("username"));
        }
    }

    @Test
    void shardingDataSourceCanBeCreatedWithStandaloneMode() throws Exception {
        var props = new ReserveXProperties();
        props.getDatasource().put("ds0", dataSource("ds0"));
        props.getDatasource().put("ds1", dataSource("ds1"));

        DataSource dataSource = new ShardingDataSourceConfig().shardingDataSource(props);
        ((AutoCloseable) dataSource).close();
    }

    private static ReserveXProperties.DataSourceProps dataSource(String name) {
        var result = new ReserveXProperties.DataSourceProps();
        result.setUrl("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        result.setUsername("sa");
        result.setPassword("");
        return result;
    }
}
