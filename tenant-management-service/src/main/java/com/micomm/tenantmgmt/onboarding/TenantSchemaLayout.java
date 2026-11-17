package com.micomm.tenantmgmt.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
 * <li>{@link #CLUB_SCOPED} ("3") - the notifications tables plus product modules and
 * global templates, with club_id on every table (pinned to the schema's club by a
 * CHECK). Kept for the schemas already created with it.</li>
 * <li>{@link #CLUB_SCHEMA} ("4") - the same tables as "3" without club_id: the
 * schema itself is the club. Kept for the schemas already created with it.</li>
 * <li>{@link #CHANNEL_DELIVERIES} ("5") - "4" with sends normalised: notifications (with
 * type TRANSACTIONAL / CAMPAIGN) → notification_deliveries (one per channel) →
 * notification_recipients. Kept for the schemas already created with it.</li>
 * <li>{@link #LEAN_RECIPIENTS} ("6") - "5" with notification_recipients reduced to what
 * cannot be derived (no product_id or created_at). Kept for the schemas already created with it.</li>
 * <li>{@link #PROVIDER_EVENTS} ("7") - "6" plus notification_provider_events: every provider
 * webhook event per recipient (e.g. SendGrid processed / delivered / bounce). Kept for the schemas
 * already created with it.</li>
 * <li>{@link #PER_CHANNEL_TEMPLATES} ("8") - "7" with what differs per channel on the channel:
 * notification_deliveries gets template_id / global_template_id (foreign keys) and subject (a
 * searchable column, out of content); notifications loses template_id and recipient_count (the
 * send's total is the sum of its deliveries). Used for every club schema created from now on.</li>
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
            ClubScopedSchemaDefinition::createTableStatements),

    CLUB_SCHEMA("4",
            List.of("notification_recipients", "notifications", "audit_logs"),
            clubId -> ClubSchemaDefinition.createTableStatements()),

    CHANNEL_DELIVERIES("5",
            List.of("notification_recipients", "notification_deliveries", "notifications", "audit_logs"),
            clubId -> ChannelDeliverySchemaDefinition.createTableStatements(false)),

    LEAN_RECIPIENTS("6",
            List.of("notification_recipients", "notification_deliveries", "notifications", "audit_logs"),
            clubId -> ChannelDeliverySchemaDefinition.createTableStatements(true)),

    PROVIDER_EVENTS("7",
            List.of("notification_provider_events", "notification_recipients", "notification_deliveries",
                    "notifications", "audit_logs"),
            clubId -> {
                List<String> statements = new ArrayList<>(ChannelDeliverySchemaDefinition.createTableStatements(true));
                statements.addAll(ChannelDeliverySchemaDefinition.providerEventStatements());
                return statements;
            }),

    PER_CHANNEL_TEMPLATES("8",
            List.of("notification_provider_events", "notification_recipients", "notification_deliveries",
                    "notifications", "audit_logs"),
            clubId -> {
                List<String> statements = new ArrayList<>(
                        ChannelDeliverySchemaDefinition.createTableStatements(true, true));
                statements.addAll(ChannelDeliverySchemaDefinition.providerEventStatements());
                return statements;
            });

    /** Layout given to schemas that do not exist yet. */
    public static final TenantSchemaLayout FOR_NEW_SCHEMAS = PER_CHANNEL_TEMPLATES;

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

    public static Optional<TenantSchemaLayout> fromVersion(String version) {
        return Arrays.stream(values()).filter(l -> l.version.equals(version)).findFirst();
    }

    /** Product modules, module templates and global templates: layouts "3" to "8". */
    public boolean supportsModules() {
        return this == CLUB_SCOPED || this == CLUB_SCHEMA || this == CHANNEL_DELIVERIES || this == LEAN_RECIPIENTS
                || this == PROVIDER_EVENTS || this == PER_CHANNEL_TEMPLATES;
    }

    /** Whether tables carry a club_id column that statements must set and filter on (layout "3" only). */
    public boolean hasClubIdColumn() {
        return this == CLUB_SCOPED;
    }

    /** True if a tenant_databases.schema_version supports modules. */
    public static boolean supportsModules(String version) {
        return fromVersion(version).map(TenantSchemaLayout::supportsModules).orElse(false);
    }

    /** True if a tenant_databases.schema_version has club_id columns. */
    public static boolean hasClubIdColumn(String version) {
        return fromVersion(version).map(TenantSchemaLayout::hasClubIdColumn).orElse(false);
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
        if (Boolean.TRUE.equals(scoped.queryForObject(
                "SELECT to_regclass('notification_deliveries') IS NOT NULL", Boolean.class))) {
            // "6" dropped product_id from recipients; "5" still has it. "7" is "6" plus provider events;
            // "8" moved the template (and subject) to the deliveries.
            if (hasColumn(scoped, "notification_recipients", "product_id")) {
                return CHANNEL_DELIVERIES;
            }
            if (hasColumn(scoped, "notification_deliveries", "template_id")) {
                return PER_CHANNEL_TEMPLATES;
            }
            return Boolean.TRUE.equals(scoped.queryForObject(
                    "SELECT to_regclass('notification_provider_events') IS NOT NULL", Boolean.class))
                    ? PROVIDER_EVENTS : LEAN_RECIPIENTS;
        }
        if (hasColumn(scoped, "notifications", "club_id")) {
            return CLUB_SCOPED;
        }
        // "4" has the module columns of "3" without club_id; "2" has neither.
        return hasColumn(scoped, "notifications", "module_code") ? CLUB_SCHEMA : NOTIFICATIONS;
    }

    private static boolean hasColumn(JdbcTemplate scoped, String table, String column) {
        return Boolean.TRUE.equals(scoped.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ?"
                        + " AND column_name = ?)", Boolean.class, table, column));
    }
}
