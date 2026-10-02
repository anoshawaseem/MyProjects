package com.micomm.tenantmgmt.onboarding;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "micomm.provisioning")
public class TenantProvisioningProperties {

    private String host = "localhost";
    private int port = 5432;
    private String sharedDatabaseName = "micomm_platform";
    private String adminUsername;
    private String adminPassword;
    private String tenantMigrationLocation = "classpath:db/migration/tenant";

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + sharedDatabaseName;
    }

    // getters/setters
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getSharedDatabaseName() { return sharedDatabaseName; }
    public void setSharedDatabaseName(String sharedDatabaseName) { this.sharedDatabaseName = sharedDatabaseName; }

    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }

    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }

    public String getTenantMigrationLocation() { return tenantMigrationLocation; }
    public void setTenantMigrationLocation(String tenantMigrationLocation) { this.tenantMigrationLocation = tenantMigrationLocation; }
}