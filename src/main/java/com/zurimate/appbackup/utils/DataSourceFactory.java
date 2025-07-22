package com.zurimate.appbackup.utils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zurimate.appbackup.dto.DBConfig;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Objects;

@Slf4j
public final class DataSourceFactory {
    private static final Map<DBType, TriFunction<String, Integer, String, String>> jdbcUrlMap = Map.of(
//            DBType.MYSQL, (host, port, dbName) -> "jdbc:mysql://%s:%s/%s?zeroDateTimeBehavior=convertToNull".formatted(host,port,dbName)
            DBType.MYSQL, "jdbc:mysql://%s:%s/%s?zeroDateTimeBehavior=convertToNull"::formatted
    );

    private DataSourceFactory() {
    }

    public static DataSource createDataSource(DBType dbType, DBConfig dbConfig) {
        var jdbcUrlFunc = jdbcUrlMap.getOrDefault(dbType, null);
        if (Objects.isNull(jdbcUrlFunc)) {
            throw new RuntimeException("jdbcUrlMap does not have " + dbType);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrlFunc.apply(Helpers.parseHost(dbConfig.host()), dbConfig.port(), dbConfig.dbName()));
        config.setUsername(dbConfig.username());
        config.setPassword(dbConfig.password());
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        return new HikariDataSource(config);
    }

}
