package com.micomm.common.tenant;

import java.util.UUID;

/**
 * Minimal info needed to build a DataSource for a tenant database.
 * Populated by querying micomm_control.tenant_databases (via API or direct query).
 */
public record TenantDatabaseInfo(
        UUID clubId,
        String databaseIdentifier,
        String databaseHostReference,
        String username,
        String password
) {
    public String jdbcUrl() {
        return String.format("jdbc:postgresql://%s/%s", databaseHostReference, databaseIdentifier);
    }
}