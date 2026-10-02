package com.micomm.tenantmgmt;

import com.micomm.common.security.JwtProperties;
import com.micomm.tenantmgmt.onboarding.TenantProvisioningProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.micomm.tenantmgmt", "com.micomm.common"})
@EnableConfigurationProperties({JwtProperties.class, TenantProvisioningProperties.class})
public class TenantManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(TenantManagementApplication.class, args);
    }
}