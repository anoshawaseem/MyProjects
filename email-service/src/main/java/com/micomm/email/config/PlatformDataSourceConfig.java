package com.micomm.email.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Shared connection pool to the single physical "micomm_platform" database
 * that hosts every tenant's schema (tenant_{club}_{product}). The active
 * schema is selected per-operation via SET search_path, not via multiple
 * DataSources.
 */
@Configuration
public class PlatformDataSourceConfig {

    @Bean
    public DataSource platformDataSource(PlatformDatabaseProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.jdbcUrl());
        config.setUsername(props.getAdminUsername());
        config.setPassword(props.getAdminPassword());
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setPoolName("email-service-platform-pool");
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate platformJdbcTemplate(DataSource platformDataSource) {
        return new JdbcTemplate(platformDataSource);
    }
}