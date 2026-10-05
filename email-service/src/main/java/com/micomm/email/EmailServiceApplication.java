package com.micomm.email;

import com.micomm.common.security.JwtProperties;
import com.micomm.email.config.PlatformDatabaseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.micomm.email", "com.micomm.common"})
@EnableConfigurationProperties({JwtProperties.class, PlatformDatabaseProperties.class})
public class EmailServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmailServiceApplication.class, args);
    }
}