package com.micomm.tenantmgmt.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Run this once to generate a bcrypt hash for seeding a test user.
 * Not a real test — just a throwaway utility.
 */
public class PasswordHashGenerator {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String rawPassword = "changeme123";
        String hash = encoder.encode(rawPassword);
        System.out.println("Password: " + rawPassword);
        System.out.println("Bcrypt hash: " + hash);
    }
}