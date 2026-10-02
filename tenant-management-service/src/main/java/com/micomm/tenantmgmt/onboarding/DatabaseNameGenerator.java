package com.micomm.tenantmgmt.onboarding;

/**
 * Converts an organization slug into a safe Postgres database identifier.
 * e.g. "royal-freshwater" -> "micomm_tenant_royal_freshwater"
 */
public final class DatabaseNameGenerator {

    private DatabaseNameGenerator() {}

    public static String forSlug(String slug) {
        String sanitized = slug
                .toLowerCase()
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");

        String candidate = "micomm_tenant_" + sanitized;

        // Postgres identifier limit is 63 bytes; truncate defensively.
        if (candidate.length() > 63) {
            candidate = candidate.substring(0, 63);
        }
        return candidate;
    }
}