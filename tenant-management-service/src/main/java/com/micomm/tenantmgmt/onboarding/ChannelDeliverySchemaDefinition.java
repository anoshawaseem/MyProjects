package com.micomm.tenantmgmt.onboarding;

import java.util.ArrayList;
import java.util.List;

/**
 * Idempotent DDL for the channel-deliveries layouts: {@link TenantSchemaLayout#CHANNEL_DELIVERIES}
 * ("5"), {@link TenantSchemaLayout#LEAN_RECIPIENTS} ("6") and {@link TenantSchemaLayout#PROVIDER_EVENTS}
 * ("7", "6" plus provider events) and {@link TenantSchemaLayout#PER_CHANNEL_TEMPLATES} ("8", "7" with
 * template and subject per channel; used for every club schema created from now on). Same as
 * layout "4" (no club_id: the schema is the club), with sends normalised so one notification can
 * go out on several channels:
 *
 * <pre>
 * notifications            one row per send: product, module, type, overall status (and, up to
 *   │                      layout "7", template and recipient count)
 *   └─ notification_deliveries   one row per channel of the send: content (JSONB), provider,
 *        │                       status and counts (layout "8": also template and subject)
 *        └─ notification_recipients   one row per address on that channel
 * </pre>
 *
 * <ul>
 * <li>type is TRANSACTIONAL (account / membership messages) or CAMPAIGN (marketing).</li>
 * <li>content holds the channel's own fields, e.g. EMAIL {"subject", "html", "text"}, SMS
 * {"text"}, PUSH {"title", "body", "data"}, WHATSAPP {"template", "language", "params"}.</li>
 * <li>Deliveries reference their notification including product_id, so a delivery can never
 * carry a different product than its send; all three are partitioned by the send's
 * created_at.</li>
 * <li>Recipients: layout "5" also stores product_id (tied to the delivery's by the foreign key)
 * and its own created_at. Layout "6" drops both: the product comes from the delivery, and
 * notification_created_at (the partition key, which has to be stored) already dates the row.</li>
 * </ul>
 *
 * Must run with search_path set to the tenant schema on a single connection
 * (see TenantOnboardingService#inSchema).
 */
public final class ChannelDeliverySchemaDefinition {

    private ChannelDeliverySchemaDefinition() {
    }

    /** @param leanRecipients true for layout "6" (recipients without product_id / created_at) */
    public static List<String> createTableStatements(boolean leanRecipients) {
        return createTableStatements(leanRecipients, false);
    }

    /**
     * @param leanRecipients      true for layouts "6" to "8" (recipients without product_id / created_at)
     * @param perChannelTemplates true for layout "8": template and subject per delivery, no
     *                            template_id / recipient_count on notifications
     */
    public static List<String> createTableStatements(boolean leanRecipients, boolean perChannelTemplates) {
        List<String> statements = new ArrayList<>();
        List<String> tables = new ArrayList<>(tables());
        if (perChannelTemplates) {
            tables.add(PER_CHANNEL_NOTIFICATIONS);
            tables.add(PER_CHANNEL_DELIVERIES);
        } else {
            tables.add(NOTIFICATIONS);
            tables.add(DELIVERIES);
        }
        tables.add(AUDIT_LOGS);
        tables.add(leanRecipients ? LEAN_RECIPIENTS : RECIPIENTS_WITH_PRODUCT);
        for (String statement : tables) {
            statements.add(statement
                    .replace("{CHANNEL_CHECK}", NotificationSchemaDefinition.CHANNEL_CHECK)
                    .replace("{MODULE_CHECK}", ClubScopedSchemaDefinition.MODULE_CHECK));
        }
        statements.addAll(TenantSchemaDefinition.partitionFunctions());
        statements.addAll(indexes());
        statements.addAll(leanRecipients ? LEAN_RECIPIENT_INDEXES : PRODUCT_RECIPIENT_INDEXES);
        if (perChannelTemplates) {
            statements.addAll(PER_CHANNEL_TEMPLATE_INDEXES);
        }
        for (String table : TenantSchemaLayout.CHANNEL_DELIVERIES.partitionedTablesDropOrder()) {
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
                        """);
    }

    // One row per send, whatever channels it uses. status sums up its deliveries.
    private static final String NOTIFICATIONS = """
                        CREATE TABLE IF NOT EXISTS notifications (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
                            product_id UUID NOT NULL,
                            module_code VARCHAR(50) {MODULE_CHECK},
                            type VARCHAR(20) NOT NULL DEFAULT 'TRANSACTIONAL'
                                CONSTRAINT chk_notifications_type CHECK (type IN ('TRANSACTIONAL', 'CAMPAIGN')),
                            template_id UUID,
                            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                            recipient_count INTEGER NOT NULL DEFAULT 0,
                            sent_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT pk_notifications PRIMARY KEY (id, created_at),
                            CONSTRAINT uq_notifications_product UNIQUE (id, created_at, product_id)
                        ) PARTITION BY RANGE (created_at)
                        """;

    // One row per channel of a send: what was sent on it, through which provider, and how it went.
    private static final String DELIVERIES = """
                        CREATE TABLE IF NOT EXISTS notification_deliveries (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
                            notification_id UUID NOT NULL,
                            notification_created_at TIMESTAMPTZ NOT NULL,
                            product_id UUID NOT NULL,
                            channel VARCHAR(20) NOT NULL {CHANNEL_CHECK},
                            provider_account_id UUID,
                            sender VARCHAR(320),
                            content JSONB NOT NULL,
                            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                            recipient_count INTEGER NOT NULL DEFAULT 0,
                            success_count INTEGER NOT NULL DEFAULT 0,
                            failure_count INTEGER NOT NULL DEFAULT 0,
                            sent_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT pk_notification_deliveries PRIMARY KEY (id, notification_created_at),
                            CONSTRAINT uq_notification_deliveries_channel UNIQUE (notification_id, notification_created_at, channel),
                            CONSTRAINT uq_notification_deliveries_product UNIQUE (id, notification_created_at, product_id),
                            CONSTRAINT chk_notification_deliveries_email_subject
                                CHECK (channel <> 'EMAIL' OR content ? 'subject'),
                            CONSTRAINT fk_notification_deliveries_notification
                                FOREIGN KEY (notification_id, notification_created_at, product_id)
                                REFERENCES notifications (id, created_at, product_id) ON DELETE CASCADE
                        ) PARTITION BY RANGE (notification_created_at)
                        """;

    /**
     * Layout "8" send: only what is true of the whole send. Which template each channel used and
     * how many recipients it had are per channel, so they live on the deliveries; the send's
     * total is their sum.
     */
    private static final String PER_CHANNEL_NOTIFICATIONS = """
                        CREATE TABLE IF NOT EXISTS notifications (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
                            product_id UUID NOT NULL,
                            module_code VARCHAR(50) {MODULE_CHECK},
                            type VARCHAR(20) NOT NULL DEFAULT 'TRANSACTIONAL'
                                CONSTRAINT chk_notifications_type CHECK (type IN ('TRANSACTIONAL', 'CAMPAIGN')),
                            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                            sent_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT pk_notifications PRIMARY KEY (id, created_at),
                            CONSTRAINT uq_notifications_product UNIQUE (id, created_at, product_id)
                        ) PARTITION BY RANGE (created_at)
                        """;

    /**
     * Layout "8" delivery: the channel's own template and subject as columns.
     *
     * <ul>
     * <li>A send's template is a module template or a club-wide global template, so there is one
     * column for each, both real foreign keys, at most one set (neither for inline content).
     * Deleting a template keeps the history: content still holds what was sent.</li>
     * <li>subject is the subject as sent, before per-recipient merge fields are filled in; EMAIL
     * needs one. content holds the body only, so the subject is never stored twice.</li>
     * </ul>
     */
    private static final String PER_CHANNEL_DELIVERIES = """
                        CREATE TABLE IF NOT EXISTS notification_deliveries (
                            id UUID NOT NULL DEFAULT gen_random_uuid(),
                            notification_id UUID NOT NULL,
                            notification_created_at TIMESTAMPTZ NOT NULL,
                            product_id UUID NOT NULL,
                            channel VARCHAR(20) NOT NULL {CHANNEL_CHECK},
                            template_id UUID,
                            global_template_id UUID,
                            subject VARCHAR(500),
                            provider_account_id UUID,
                            sender VARCHAR(320),
                            content JSONB NOT NULL,
                            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                            recipient_count INTEGER NOT NULL DEFAULT 0,
                            success_count INTEGER NOT NULL DEFAULT 0,
                            failure_count INTEGER NOT NULL DEFAULT 0,
                            sent_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT pk_notification_deliveries PRIMARY KEY (id, notification_created_at),
                            CONSTRAINT uq_notification_deliveries_channel UNIQUE (notification_id, notification_created_at, channel),
                            CONSTRAINT uq_notification_deliveries_product UNIQUE (id, notification_created_at, product_id),
                            CONSTRAINT chk_notification_deliveries_one_template
                                CHECK (num_nonnulls(template_id, global_template_id) <= 1),
                            CONSTRAINT chk_notification_deliveries_email_subject
                                CHECK (channel <> 'EMAIL' OR subject IS NOT NULL),
                            CONSTRAINT chk_notification_deliveries_content_no_subject
                                CHECK (NOT content ? 'subject'),
                            CONSTRAINT fk_notification_deliveries_notification
                                FOREIGN KEY (notification_id, notification_created_at, product_id)
                                REFERENCES notifications (id, created_at, product_id) ON DELETE CASCADE,
                            CONSTRAINT fk_notification_deliveries_template
                                FOREIGN KEY (template_id) REFERENCES notification_templates (id) ON DELETE SET NULL,
                            CONSTRAINT fk_notification_deliveries_global_template
                                FOREIGN KEY (global_template_id) REFERENCES global_templates (id) ON DELETE SET NULL
                        ) PARTITION BY RANGE (notification_created_at)
                        """;

    private static final String AUDIT_LOGS = """
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
                        """;

    /**
     * Layout "5" recipients: one row per address on one channel (the channel comes from the
     * delivery), with product_id tied to the delivery's by the foreign key, and its own created_at.
     */
    private static final String RECIPIENTS_WITH_PRODUCT = """
            CREATE TABLE IF NOT EXISTS notification_recipients (
                id UUID NOT NULL DEFAULT gen_random_uuid(),
                delivery_id UUID NOT NULL,
                notification_created_at TIMESTAMPTZ NOT NULL,
                product_id UUID NOT NULL,
                address VARCHAR(320) NOT NULL,
                status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                provider_message_id VARCHAR(255),
                error_message TEXT,
                sent_at TIMESTAMPTZ,
                delivered_at TIMESTAMPTZ,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                CONSTRAINT pk_notification_recipients PRIMARY KEY (id, notification_created_at),
                CONSTRAINT fk_notification_recipients_delivery
                    FOREIGN KEY (delivery_id, notification_created_at, product_id)
                    REFERENCES notification_deliveries (id, notification_created_at, product_id) ON DELETE CASCADE
            ) PARTITION BY RANGE (notification_created_at)
            """;

    /**
     * Layout "6" recipients: one row per address on one channel, nothing that can be derived. The
     * product and channel come from the delivery; notification_created_at is the partition key
     * (and part of the delivery's primary key), so it is the one copied column that has to stay.
     */
    private static final String LEAN_RECIPIENTS = """
            CREATE TABLE IF NOT EXISTS notification_recipients (
                id UUID NOT NULL DEFAULT gen_random_uuid(),
                delivery_id UUID NOT NULL,
                notification_created_at TIMESTAMPTZ NOT NULL,
                address VARCHAR(320) NOT NULL,
                status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                provider_message_id VARCHAR(255),
                error_message TEXT,
                sent_at TIMESTAMPTZ,
                delivered_at TIMESTAMPTZ,
                CONSTRAINT pk_notification_recipients PRIMARY KEY (id, notification_created_at),
                CONSTRAINT fk_notification_recipients_delivery
                    FOREIGN KEY (delivery_id, notification_created_at)
                    REFERENCES notification_deliveries (id, notification_created_at) ON DELETE CASCADE
            ) PARTITION BY RANGE (notification_created_at)
            """;

    /**
     * Layout "7": layout "6" plus notification_provider_events, the provider's own history of each
     * recipient (e.g. SendGrid processed / delivered / bounce / open), one row per webhook event.
     * Must run after {@link #createTableStatements(boolean)} with leanRecipients = true (it needs
     * the partition helpers and references the lean recipients' primary key).
     *
     * <ul>
     * <li>event_type is the provider's own word, unchecked, so a new provider event never breaks
     * the webhook; outcome is MiComm's provider-neutral reading of it.</li>
     * <li>provider_event_id (SendGrid sg_event_id) makes redelivered webhooks a no-op via
     * ON CONFLICT DO NOTHING; NULLs are distinct, so providers without event ids still insert.</li>
     * <li>The provider and account come from the recipient's delivery, so they are not repeated.</li>
     * <li>Partitioned with the recipient by the send's created_at, so events go with their send.</li>
     * </ul>
     */
    public static List<String> providerEventStatements() {
        List<String> statements = new ArrayList<>();
        statements.add("""
                CREATE TABLE IF NOT EXISTS notification_provider_events (
                    id UUID NOT NULL DEFAULT gen_random_uuid(),
                    recipient_id UUID NOT NULL,
                    notification_created_at TIMESTAMPTZ NOT NULL,
                    provider_event_id VARCHAR(100),
                    event_type VARCHAR(50) NOT NULL,
                    event_subtype VARCHAR(50),
                    outcome VARCHAR(20) NOT NULL
                        CONSTRAINT chk_notification_provider_events_outcome CHECK (outcome IN (
                            'ACCEPTED', 'DEFERRED', 'DELIVERED', 'BOUNCED', 'DROPPED',
                            'OPENED', 'CLICKED', 'COMPLAINED', 'UNSUBSCRIBED')),
                    smtp_status VARCHAR(20),
                    reason TEXT,
                    event_at TIMESTAMPTZ NOT NULL,
                    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    payload JSONB NOT NULL,
                    CONSTRAINT pk_notification_provider_events PRIMARY KEY (id, notification_created_at),
                    CONSTRAINT uq_notification_provider_events_event
                        UNIQUE (recipient_id, provider_event_id, notification_created_at),
                    CONSTRAINT fk_notification_provider_events_recipient
                        FOREIGN KEY (recipient_id, notification_created_at)
                        REFERENCES notification_recipients (id, notification_created_at) ON DELETE CASCADE
                ) PARTITION BY RANGE (notification_created_at)
                """);
        // A recipient's history in the order it happened.
        statements.add("""
                CREATE INDEX IF NOT EXISTS idx_notification_provider_events_recipient
                ON notification_provider_events (recipient_id, event_at)
                """);
        statements.add(TenantSchemaDefinition.ensurePartitionsStatement(
                "notification_provider_events", TenantSchemaDefinition.DEFAULT_MONTHS_AHEAD));
        return statements;
    }

    // "What was sent to this address?" — per product in layout "5"; in "6" the product comes from
    // a join with the delivery (one club per schema, few products, so the address index is enough).
    private static final List<String> PRODUCT_RECIPIENT_INDEXES = List.of("""
            CREATE INDEX IF NOT EXISTS idx_notification_recipients_product_address
            ON notification_recipients (product_id, address)
            """);
    private static final List<String> LEAN_RECIPIENT_INDEXES = List.of("""
            CREATE INDEX IF NOT EXISTS idx_notification_recipients_address
            ON notification_recipients (address)
            """);
    // Layout "8": "sends that used this template", and what ON DELETE SET NULL looks up when a
    // template is deleted (without them that is a scan of every delivery partition).
    private static final List<String> PER_CHANNEL_TEMPLATE_INDEXES = List.of("""
            CREATE INDEX IF NOT EXISTS idx_notification_deliveries_template
            ON notification_deliveries (template_id)
            WHERE template_id IS NOT NULL
            """, """
            CREATE INDEX IF NOT EXISTS idx_notification_deliveries_global_template
            ON notification_deliveries (global_template_id)
            WHERE global_template_id IS NOT NULL
            """);

    /** Declared on the partitioned parents, so every (future) partition gets them automatically. */
    private static List<String> indexes() {
        return List.of(
                // Send history: newest first per product, optionally one type or module.
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_product_created
                        ON notifications (product_id, created_at DESC, id DESC)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_product_type_created
                        ON notifications (product_id, type, created_at DESC, id DESC)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_notifications_product_module_created
                        ON notifications (product_id, module_code, created_at DESC, id DESC)
                        WHERE module_code IS NOT NULL
                        """,
                // "Email sends of this product", newest first.
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_deliveries_product_channel_created
                        ON notification_deliveries (product_id, channel, notification_created_at DESC)
                        """,
                // Recipients of one delivery (paged by id), and failed ones only.
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_delivery
                        ON notification_recipients (delivery_id, id)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_failed
                        ON notification_recipients (delivery_id, id)
                        WHERE status = 'FAILED'
                        """,
                // Delivery receipts / webhooks look recipients up by the provider's message id.
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_recipients_provider_msg
                        ON notification_recipients (provider_message_id)
                        WHERE provider_message_id IS NOT NULL
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_notification_templates_product_module
                        ON notification_templates (product_id, module_code, channel, created_at DESC)
                        """,
                """
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_templates_key
                        ON notification_templates (product_id, module_code, channel, template_key)
                        WHERE template_key IS NOT NULL
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_global_templates_channel
                        ON global_templates (channel, created_at DESC)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_audit_logs_created
                        ON audit_logs (created_at DESC, id DESC)
                        """);
    }
}
