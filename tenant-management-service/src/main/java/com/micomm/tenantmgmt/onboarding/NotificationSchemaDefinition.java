package com.micomm.tenantmgmt.onboarding;

import java.util.ArrayList;
import java.util.List;

/**
 * Idempotent DDL for the {@link TenantSchemaLayout#NOTIFICATIONS} layout ("2"),
 * used for every club schema created from now on. One channel-aware set of
 * tables replaces the per-channel email_* / sms_* tables:
 *
 * <ul>
 * <li>notifications - one row per send (bulk or single), any channel;
 * partitioned by month on created_at</li>
 * <li>notification_recipients - one row per recipient; partitioned by month on
 * the parent's created_at (notification_created_at) so a send and its
 * recipients always share a partition</li>
 * <li>notification_templates - templates per product and channel (small, not
 * partitioned)</li>
 * </ul>
 *
 * Fields shared by channels are real columns; rarely used channel-specific
 * fields go in channel_data (JSONB), so a new channel needs no schema change
 * beyond the channel CHECK. Must run with search_path set to the tenant schema
 * on a single connection (see TenantOnboardingService#inSchema).
 */
public final class NotificationSchemaDefinition {

    static final String CHANNEL_CHECK = "CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'WHATSAPP'))";

    private NotificationSchemaDefinition() {
    }

    public static List<String> createTableStatements() {
        List<String> statements = new ArrayList<>();
        statements.addAll(plainTables());
        statements.addAll(TenantSchemaDefinition.partitionFunctions());
        statements.addAll(partitionedTables());
        statements.addAll(indexes());
        for (String table : TenantSchemaLayout.NOTIFICATIONS.partitionedTablesDropOrder()) {
            statements.add(TenantSchemaDefinition.ensurePartitionsStatement(
                    table, TenantSchemaDefinition.DEFAULT_MONTHS_AHEAD));
        }
        return statements;
    }

    private static List<String> plainTables() {
        return List.of(
                """
                        CREATE TABLE IF NOT EXISTS notification_templates (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            product_id UUID NOT NULL,
                            channel VARCHAR(20) NOT NULL %s,
                            name VARCHAR(255) NOT NULL,
                            subject VARCHAR(500),
                            body TEXT NOT NULL,
                            html_body TEXT,
                            channel_data JSONB,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """.formatted(CHANNEL_CHECK),
                """
                        CREATE TABLE IF NOT EXISTS campaigns (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            product_id UUID,
                            name VARCHAR(255) NOT NULL,
                            channel VARCHAR(20) NOT NULL %s,
                            status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
                            scheduled_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """.formatted(CHANNEL_CHECK),
                """
                        CREATE TABLE IF NOT EXISTS campaign_recipients (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
                            contact_value VARCHAR(320) NOT NULL,
                            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """,
                """
                        CREATE TABLE IF NOT EXISTS usage_counters (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            product_id UUID,
                            metric_name VARCHAR(100) NOT NULL,
                            metric_value BIGINT NOT NULL DEFAULT 0,
                            period_start TIMESTAMPTZ NOT NULL,
                            period_end TIMESTAMPTZ NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """,
                // email-service's INSERT ... ON CONFLICT usage upsert relies on this.
                """
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_usage_counters_product_metric_period
                        ON usage_counters (product_id, metric_name, period_start)
                        """);
    }

    private static List<String> partitionedTables() {
        return List.of(
                """
                        CREATE TABLE IF NOT EXISTS notifications (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
                            product_id UUID NOT NULL,
                            channel VARCHAR(20) NOT NULL %s,
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
                            CONSTRAINT chk_notifications_email_subject CHECK (channel <> 'EMAIL' OR subject IS NOT NULL)
                        ) PARTITION BY RANGE (created_at)
                        """.formatted(CHANNEL_CHECK),
                // channel is copied from the parent so recipient lookups can filter on it directly.
                """
                        CREATE TABLE IF NOT EXISTS notification_recipients (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
                            notification_id UUID NOT NULL,
                            notification_created_at TIMESTAMPTZ NOT NULL,
                            channel VARCHAR(20) NOT NULL %s,
                            address VARCHAR(320) NOT NULL,
                            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                            provider_message_id VARCHAR(255),
                            error_message TEXT,
                            sent_at TIMESTAMPTZ,
                            delivered_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT pk_notification_recipients PRIMARY KEY (id, notification_created_at),
                            CONSTRAINT fk_notification_recipients_notification
                                FOREIGN KEY (notification_id, notification_created_at)
                                REFERENCES notifications (id, created_at) ON DELETE CASCADE
                        ) PARTITION BY RANGE (notification_created_at)
                        """.formatted(CHANNEL_CHECK),
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
                // "Everything for this product, newest first" across channels, keyset-paged on (created_at, id).
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_product_created
                        ON notifications (product_id, created_at DESC, id DESC)
                        """,
                // Same, for one channel: without it a rare channel would be filtered out of the index above.
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_product_channel_created
                        ON notifications (product_id, channel, created_at DESC, id DESC)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_notification
                        ON notification_recipients (notification_id, id)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_failed
                        ON notification_recipients (notification_id, id)
                        WHERE status = 'FAILED'
                        """,
                // Delivery receipts / webhooks look recipients up by the provider's message id.
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_provider_msg
                        ON notification_recipients (provider_message_id)
                        WHERE provider_message_id IS NOT NULL
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_templates_product_channel
                        ON notification_templates (product_id, channel)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_campaigns_product
                        ON campaigns (product_id)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_audit_logs_product_created
                        ON audit_logs (product_id, created_at DESC)
                        """);
    }
}
