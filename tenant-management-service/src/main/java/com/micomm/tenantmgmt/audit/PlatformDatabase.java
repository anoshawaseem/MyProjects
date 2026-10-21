package com.micomm.tenantmgmt.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.micomm.tenantmgmt.onboarding.TenantProvisioningProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

/**
 * A small pooled connection to the platform database that holds the club schemas, for audit
 * reads and writes. Deliberately not a DataSource bean: that would replace the control database
 * as Spring Boot's default DataSource. Statements must use schema-qualified table names.
 */
@Component
public class PlatformDatabase {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public PlatformDatabase(TenantProvisioningProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername(properties.getAdminUsername());
        config.setPassword(properties.getAdminPassword());
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        config.setPoolName("tenant-mgmt-platform-pool");
        // Connect on first use, so startup does not depend on the platform database.
        config.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(config);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}
