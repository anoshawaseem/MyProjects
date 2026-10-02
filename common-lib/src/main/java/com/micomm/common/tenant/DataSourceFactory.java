package com.micomm.common.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Builds a pooled DataSource for a resolved tenant database.
 */
public final class DataSourceFactory {

    private DataSourceFactory() {}

    public static DataSource build(TenantDatabaseInfo info) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(info.jdbcUrl());
        config.setUsername(info.username());
        config.setPassword(info.password());
        config.setMaximumPoolSize(5);
        config.setPoolName("tenant-" + info.databaseIdentifier());
        return new HikariDataSource(config);
    }
}