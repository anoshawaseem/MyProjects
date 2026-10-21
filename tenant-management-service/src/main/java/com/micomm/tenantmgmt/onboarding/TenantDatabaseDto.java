package com.micomm.tenantmgmt.onboarding;

import java.util.List;

/**
 * enabledModules: codes of the product modules the club has enabled (club-scoped schemas only).
 * clubName / productName fill the {{clubName}} / {{productName}} merge fields of sends.
 */
public record TenantDatabaseDto(String schemaName, String schemaVersion, List<String> enabledModules,
        String clubName, String productName) {}
