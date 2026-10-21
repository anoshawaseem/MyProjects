package com.micomm.tenantmgmt.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Table layout of a club schema, persisted as tenant_databases.schema_version
 * and passed to email-service so it reads/writes the right tables.
 *
 * <ul>
 * <li>{@link #LEGACY} ("1") - per-channel tables (email_logs, email_log_recipients,
 * sms_logs, email_templates, sms_templates). Club schemas created before the
 * notifications layout keep this layout and their data untouched.</li>
 * <li>{@link #NOTIFICATIONS} ("2") - one channel-aware set of tables
 * (notifications, notification_recipients, notification_templates), scoped
 * by product_id only. Kept for the schemas already created with it.</li>
 * <li>{@link #CLUB_SCOPED} ("3") - the notifications tables with club_id and
 * product_id on every table (club_id pinned to the schema's club by a CHECK)
 * and indexes led by (club_id, product_id). Used for every club schema
 * created from now on.</li>
 * </ul>
 *
 * A schema never changes layout: the layout is detected from the tables that
 * exist, so adding a product to an existing club keeps that club's layout.
 */
public enum TenantSchemaLayout {

    LEGACY("1",
            List.of("email_log_recipients", "email_logs", "sms_logs", "audit_logs"),
            clubId -> TenantSchemaDefinition.createTableStatements()),

    NOTIFICATIONS("2",
            List.of("notification_recipients", "notifications", "audit_logs"),
            clubId -> NotificationSchemaDefinition.createTableStatements()),

    CLUB_SCOPED("3",
            List.of("notification_recipients", "notifications", "audit_logs"),
            ClubScopedSchemaDefinition::createTableStatements);

    /** Layout given to schemas that do not exist yet. */
    public static final TenantSchemaLayout FOR_NEW_SCHEMAS = CLUB_SCOPED;

    private final String version;
    private final List<String> partitionedTablesDropOrder;
    private final Function<UUID, List<String>> statements;

    TenantSchemaLayout(String version, List<String> partitionedTablesDropOrder,
            Function<UUID, List<String>> statements) {
        this.version = version;
        this.partitionedTablesDropOrder = partitionedTablesDropOrder;
        this.statements = statements;
    }

    public String version() {
        return version;
    }

    /** Partitioned tables, children before parents (the order retention must drop them in). */
    public List<String> partitionedTablesDropOrder() {
        return partitionedTablesDropOrder;
    }

    /** Idempotent DDL for this layout; {@code clubId} is the schema's owning club (used by CLUB_SCOPED). */
    public List<String> statements(UUID clubId) {
        if (this == CLUB_SCOPED && clubId == null) {
            throw new IllegalArgumentException("clubId is required for the club-scoped layout");
        }
        return statements.apply(clubId);
    }

    /** Detects the layout of an existing schema; {@code scoped} must have search_path set to it. */
    public static TenantSchemaLayout detect(JdbcTemplate scoped) {
        Boolean hasNotifications = scoped.queryForObject(
                "SELECT to_regclass('notifications') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(hasNotifications)) {
            return LEGACY;
        }
        Boolean clubScoped = scoped.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = 'notifications'"
                        + " AND column_name = 'club_id')", Boolean.class);
        return Boolean.TRUE.equals(clubScoped) ? CLUB_SCOPED : NOTIFICATIONS;
    }
}
