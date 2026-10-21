package com.micomm.tenantmgmt.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Idempotent DDL for the {@link TenantSchemaLayout#CLUB_SCOPED} layout ("3"),
 * used for every club schema created from now on. The notifications layout's
 * tables that are in use, with club_id and product_id on every table:
 *
 * <ul>
 * <li>club_id is pinned to the schema's club by a CHECK constraint (and
 * defaults to it), so a row for any other club is rejected by the database
 * even if application code routes it to the wrong schema.</li>
 * <li>notification_recipients reference their notification by
 * (id, created_at, club_id, product_id), so a recipient can never carry a
 * different club or product than its notification.</li>
 * <li>Indexes lead with (club_id, product_id), matching how every query
 * filters.</li>
 * <li>Templates belong to a product module (module_code, e.g. MEMBERSHIP);
 * notifications record the module they were sent from, when any.</li>
 * <li>global_templates hold the club's templates outside any module; they
 * have no product_id and are available in all of the club's products.</li>
 * </ul>
 *
 * audit_logs is the club's audit trail, written by both services.
 *
 * Not created here (no code uses them yet): campaigns and campaign_recipients.
 * Club schemas created before keep theirs.
 *
 * Must run with search_path set to the tenant schema on a single connection
 * (see TenantOnboardingService#inSchema).
 */
public final class ClubScopedSchemaDefinition {

    private ClubScopedSchemaDefinition() {
    }

    public static List<String> createTableStatements(UUID clubId) {
        List<String> statements = new ArrayList<>();
        for (String statement : tables()) {
            statements.add(bind(statement, clubId));
        }
        statements.addAll(TenantSchemaDefinition.partitionFunctions());
        statements.addAll(indexes());
        for (String table : TenantSchemaLayout.CLUB_SCOPED.partitionedTablesDropOrder()) {
            statements.add(TenantSchemaDefinition.ensurePartitionsStatement(
                    table, TenantSchemaDefinition.DEFAULT_MONTHS_AHEAD));
        }
        return statements;
    }

    /**
     * Module codes come from product_modules in the control database (no cross-database FK is
     * possible), so the schema enforces the code's format and the services check it is enabled.
     */
    static final String MODULE_CHECK = "CHECK (module_code ~ '^[A-Z][A-Z0-9_]*$')";

    /** Substitutes the schema's club (a UUID, so safe to inline) and the shared CHECKs. */
    private static String bind(String statement, UUID clubId) {
        return statement
                .replace("{CLUB}", "'" + clubId + "'::uuid")
                .replace("{CHANNEL_CHECK}", NotificationSchemaDefinition.CHANNEL_CHECK)
                .replace("{MODULE_CHECK}", MODULE_CHECK);
    }

    private static List<String> tables() {
        return List.of(
                """
                        CREATE TABLE IF NOT EXISTS notification_templates (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            club_id UUID NOT NULL DEFAULT {CLUB}
                                CONSTRAINT chk_notification_templates_club CHECK (club_id = {CLUB}),
                            product_id UUID NOT NULL,
                            module_code VARCHAR(50) NOT NULL {MODULE_CHECK},
                            template_key VARCHAR(100),
                            channel VARCHAR(20) NOT NULL {CHANNEL_CHECK},
                            name VARCHAR(255) NOT NULL,
                            subject VARCHAR(500),
                            body TEXT NOT NULL,
                            html_body TEXT,
                            channel_data JSONB,
                            status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                                CONSTRAINT chk_notification_templates_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
                            source_type_id UUID,
                            source_version INTEGER,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """,
                // The club's own templates outside any module: no product_id, so one row is
                // available in every product of the club (the schema holds all of them).
                """
                        CREATE TABLE IF NOT EXISTS global_templates (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            club_id UUID NOT NULL DEFAULT {CLUB}
                                CONSTRAINT chk_global_templates_club CHECK (club_id = {CLUB}),
                            channel VARCHAR(20) NOT NULL {CHANNEL_CHECK},
                            name VARCHAR(255) NOT NULL,
                            subject VARCHAR(500),
                            body TEXT NOT NULL,
                            html_body TEXT,
                            channel_data JSONB,
                            status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                                CONSTRAINT chk_global_templates_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT chk_global_templates_email_subject CHECK (channel <> 'EMAIL' OR subject IS NOT NULL)
                        )
                        """,
                """
                        CREATE TABLE IF NOT EXISTS usage_counters (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            club_id UUID NOT NULL DEFAULT {CLUB}
                                CONSTRAINT chk_usage_counters_club CHECK (club_id = {CLUB}),
                            product_id UUID NOT NULL,
                            metric_name VARCHAR(100) NOT NULL,
                            metric_value BIGINT NOT NULL DEFAULT 0,
                            period_start TIMESTAMPTZ NOT NULL,
                            period_end TIMESTAMPTZ NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT uq_usage_counters_scope_metric_period
                                UNIQUE (club_id, product_id, metric_name, period_start)
                        )
                        """,
                // Partitioned by month. The extra unique key lets recipients reference
                // (id, created_at, club_id, product_id); it must include the partition key.
                """
                        CREATE TABLE IF NOT EXISTS notifications (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
                            club_id UUID NOT NULL DEFAULT {CLUB}
                                CONSTRAINT chk_notifications_club CHECK (club_id = {CLUB}),
                            product_id UUID NOT NULL,
                            module_code VARCHAR(50) {MODULE_CHECK},
                            channel VARCHAR(20) NOT NULL {CHANNEL_CHECK},
                            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                            sender VARCHAR(320),
                            subject VARCHAR(500),
                            body TEXT,
                            html_body TEXT,
                            template_id UUID,
                            provider_account_id UUID,
                            channel_data JSONB,
                            recipient_count INTEGER NOT NULL DEFAULT 0,
                            success_count INTEGER NOT NULL DEFAULT 0,
                            failure_count INTEGER NOT NULL DEFAULT 0,
                            sent_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT pk_notifications PRIMARY KEY (id, created_at),
                            CONSTRAINT uq_notifications_scope UNIQUE (id, created_at, club_id, product_id),
                            CONSTRAINT chk_notifications_email_subject CHECK (channel <> 'EMAIL' OR subject IS NOT NULL)
                        ) PARTITION BY RANGE (created_at)
                        """,
                """
                        CREATE TABLE IF NOT EXISTS notification_recipients (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
                            notification_id UUID NOT NULL,
                            notification_created_at TIMESTAMPTZ NOT NULL,
                            club_id UUID NOT NULL DEFAULT {CLUB}
                                CONSTRAINT chk_notification_recipients_club CHECK (club_id = {CLUB}),
                            product_id UUID NOT NULL,
                            channel VARCHAR(20) NOT NULL {CHANNEL_CHECK},
                            address VARCHAR(320) NOT NULL,
                            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                            provider_message_id VARCHAR(255),
                            error_message TEXT,
                            sent_at TIMESTAMPTZ,
                            delivered_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT pk_notification_recipients PRIMARY KEY (id, notification_created_at),
                            CONSTRAINT fk_notification_recipients_notification
                                FOREIGN KEY (notification_id, notification_created_at, club_id, product_id)
                                REFERENCES notifications (id, created_at, club_id, product_id) ON DELETE CASCADE
                        ) PARTITION BY RANGE (notification_created_at)
                        """,
                // The club's audit log (see common AuditLogWriter). product_id stays nullable:
                // some events are club-wide. details is JSON text, like in the older layouts.
                """
                        CREATE TABLE IF NOT EXISTS audit_logs (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
                            club_id UUID NOT NULL DEFAULT {CLUB}
                                CONSTRAINT chk_audit_logs_club CHECK (club_id = {CLUB}),
                            product_id UUID,
                            actor_email VARCHAR(320),
                            action VARCHAR(255) NOT NULL,
                            entity_type VARCHAR(100),
                            entity_id VARCHAR(100),
                            details TEXT,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT pk_audit_logs PRIMARY KEY (id, created_at)
                        ) PARTITION BY RANGE (created_at)
                        """);
    }

    /** Declared on the partitioned parents, so every (future) partition gets them automatically. */
    private static List<String> indexes() {
        return List.of(
                // "This club + product's notifications, newest first", keyset-paged on (created_at, id).
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_scope_created
                        ON notifications (club_id, product_id, created_at DESC, id DESC)
                        """,
                // Same, for one channel.
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_scope_channel_created
                        ON notifications (club_id, product_id, channel, created_at DESC, id DESC)
                        """,
                // Recipients of one notification (paged by id), and failed ones only.
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_notification
                        ON notification_recipients (notification_id, id)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_failed
                        ON notification_recipients (notification_id, id)
                        WHERE status = 'FAILED'
                        """,
                // "What did this club + product send to this address?"
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_scope_address
                        ON notification_recipients (club_id, product_id, address)
                        """,
                // Delivery receipts / webhooks look recipients up by the provider's message id.
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_provider_msg
                        ON notification_recipients (provider_message_id)
                        WHERE provider_message_id IS NOT NULL
                        """,
                // "This module's notifications" (module screens' send history).
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_scope_module_created
                        ON notifications (club_id, product_id, module_code, created_at DESC, id DESC)
                        WHERE module_code IS NOT NULL
                        """,
                // Module screens list one module's templates for a channel, newest first.
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_templates_scope_module
                        ON notification_templates (club_id, product_id, module_code, channel, created_at DESC)
                        """,
                // One template per module template type and channel (e.g. one MEMBERSHIP /
                // RENEWAL_REMINDER email); free-form templates have no key.
                """
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_templates_key
                        ON notification_templates (club_id, product_id, module_code, channel, template_key)
                        WHERE template_key IS NOT NULL
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_global_templates_scope
                        ON global_templates (club_id, channel, created_at DESC)
                        """,
                // The audit log screen: newest first, keyset-paged on (created_at, id).
                """
                        CREATE INDEX IF NOT EXISTS idx_audit_logs_scope_created
                        ON audit_logs (club_id, created_at DESC, id DESC)
                        """);
    }
}
