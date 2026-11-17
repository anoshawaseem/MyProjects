package com.micomm.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "micomm.jwt")
public class JwtProperties {

    /** Base64-encoded secret used to sign/verify JWTs (HS256). */
    private String secret;

    /** Access token expiry in minutes. */
    private long accessTokenExpiryMinutes = 30;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenExpiryMinutes() {
        return accessTokenExpiryMinutes;
    }

    public void setAccessTokenExpiryMinutes(long accessTokenExpiryMinutes) {
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
    }
}