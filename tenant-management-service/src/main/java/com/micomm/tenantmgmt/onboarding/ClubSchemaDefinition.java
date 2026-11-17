package com.micomm.tenantmgmt.onboarding;

import java.util.ArrayList;
import java.util.List;

/**
 * Idempotent DDL for the {@link TenantSchemaLayout#CLUB_SCHEMA} layout ("4"), used for every
 * club schema created from now on. The same tables as the club-scoped layout ("3") without a
 * club_id column: each club has its own schema, so the schema itself identifies the club.
 *
 * <ul>
 * <li>Every table except global_templates and audit_logs carries product_id; indexes lead with
 * it, matching how every query filters.</li>
 * <li>notification_recipients reference their notification by (id, created_at, product_id), so a
 * recipient can never carry a different product than its notification.</li>
 * <li>Templates belong to a product module (module_code, e.g. MEMBERSHIP); notifications record
 * the module they were sent from, when any. global_templates hold the club's templates outside
 * any module and are available in all of the club's products.</li>
 * <li>audit_logs is the club's audit trail, written by both services.</li>
 * </ul>
 *
 * Must run with search_path set to the tenant schema on a single connection
 * (see TenantOnboardingService#inSchema).
 */
public final class ClubSchemaDefinition {

    private ClubSchemaDefinition() {
    }

    public static List<String> createTableStatements() {
        List<String> statements = new ArrayList<>();
        for (String statement : tables()) {
            statements.add(statement
                    .replace("{CHANNEL_CHECK}", NotificationSchemaDefinition.CHANNEL_CHECK)
                    .replace("{MODULE_CHECK}", ClubScopedSchemaDefinition.MODULE_CHECK));
        }
        statements.addAll(TenantSchemaDefinition.partitionFunctions());
        statements.addAll(indexes());
        for (String table : TenantSchemaLayout.CLUB_SCHEMA.partitionedTablesDropOrder()) {
            statements.add(TenantSchemaDefinition.ensurePartitionsStatement(
                    table, TenantSchemaDefinition.DEFAULT_MONTHS_AHEAD));
        }
        return statements;
    }

    private static List<String> tables() {
        return List.of(
                """
                        CREATE TABLE IF NOT EXISTS notification_templates (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
                            product_id UUID NOT NULL,
                            metric_name VARCHAR(100) NOT NULL,
                            metric_value BIGINT NOT NULL DEFAULT 0,
                            period_start TIMESTAMPTZ NOT NULL,
                            period_end TIMESTAMPTZ NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT uq_usage_counters_product_metric_period
                                UNIQUE (product_id, metric_name, period_start)
                        )
                        """,
                // Partitioned by month. The extra unique key lets recipients reference
                // (id, created_at, product_id); it must include the partition key.
                """
                        CREATE TABLE IF NOT EXISTS notifications (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
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
                            CONSTRAINT uq_notifications_product UNIQUE (id, created_at, product_id),
                            CONSTRAINT chk_notifications_email_subject CHECK (channel <> 'EMAIL' OR subject IS NOT NULL)
                        ) PARTITION BY RANGE (created_at)
                        """,
                """
                        CREATE TABLE IF NOT EXISTS notification_recipients (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
                            notification_id UUID NOT NULL,
                            notification_created_at TIMESTAMPTZ NOT NULL,
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
                                FOREIGN KEY (notification_id, notification_created_at, product_id)
                                REFERENCES notifications (id, created_at, product_id) ON DELETE CASCADE
                        ) PARTITION BY RANGE (notification_created_at)
                        """,
                // product_id stays nullable: some audit events are club-wide.
                """
                        CREATE TABLE IF NOT EXISTS audit_logs (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
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
                // "This product's notifications, newest first", keyset-paged on (created_at, id).
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_product_created
                        ON notifications (product_id, created_at DESC, id DESC)
                        """,
                // Same, for one channel.
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_product_channel_created
                        ON notifications (product_id, channel, created_at DESC, id DESC)
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
                // "What did this product send to this address?"
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_product_address
                        ON notification_recipients (product_id, address)
                        """,
                // Delivery receipts / webhooks look recipients up by the provider's message id.
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_provider_msg
                        ON notification_recipients (provider_message_id)
                        WHERE provider_message_id IS NOT NULL
                        """,
                // "This module's notifications" (module screens' send history).
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_product_module_created
                        ON notifications (product_id, module_code, created_at DESC, id DESC)
                        WHERE module_code IS NOT NULL
                        """,
                // Module screens list one module's templates for a channel, newest first.
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_templates_product_module
                        ON notification_templates (product_id, module_code, channel, created_at DESC)
                        """,
                // One template per module template type and channel (e.g. one MEMBERSHIP /
                // RENEWAL_REMINDER email); free-form templates have no key.
                """
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_templates_key
                        ON notification_templates (product_id, module_code, channel, template_key)
                        WHERE template_key IS NOT NULL
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_global_templates_channel
                        ON global_templates (channel, created_at DESC)
                        """,
                // The audit log screen: newest first, keyset-paged on (created_at, id).
                """
                        CREATE INDEX IF NOT EXISTS idx_audit_logs_created
                        ON audit_logs (created_at DESC, id DESC)
                        """);
    }
}
