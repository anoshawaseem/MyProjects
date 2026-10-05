package com.micomm.email.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "micomm.provisioning")
public class PlatformDatabaseProperties {

    private String host = "localhost";
    private int port = 5432;
    private String sharedDatabaseName = "micomm_platform";
    private String adminUsername;
    private String adminPassword;

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + sharedDatabaseName;
    }

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
}