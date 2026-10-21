package com.micomm.email.tenant;

import java.util.regex.Pattern;

/**
 * Builds schema-qualified table names for the per-club tenant schemas.
 *
 * Statements reference tables as "schema".table instead of relying on
 * SET search_path: JdbcTemplate borrows a (possibly different) pooled
 * connection per call, so a separate SET is neither guaranteed to apply to
 * the following statement nor reset before the connection is reused.
 */
public final class TenantTables {

    private static final Pattern VALID_SCHEMA_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,62}$");

    private TenantTables() {
    }

    public static String table(String schemaName, String tableName) {
        if (schemaName == null || !VALID_SCHEMA_NAME.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
        return "\"" + schemaName + "\"." + tableName;
    }
}
