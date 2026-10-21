package com.micomm.tenantmgmt.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Idempotent DDL for the {@link TenantSchemaLayout#LEGACY} layout ("1"):
 * per-channel tables. Applied to existing club schemas on onboarding of an
 * extra product and via /admin/tenant-migrations/apply-all; new clubs get
 * {@link NotificationSchemaDefinition} instead. Must run with search_path set
 * to the tenant schema on a single connection (see TenantOnboardingService#inSchema).
 *
 * High-volume log tables (email_logs, email_log_recipients, sms_logs,
 * audit_logs) are range-partitioned by month (UTC). Partitions are named
 * {table}_pYYYYMM and are created ahead / dropped by retention through the
 * per-schema functions ensure_monthly_partitions / drop_monthly_partitions_before,
 * called from TenantPartitionMaintenanceJob.
 */
public final class TenantSchemaDefinition {

    /** Months of partitions created ahead of the current month on every apply. */
    public static final int DEFAULT_MONTHS_AHEAD = 3;

    private static final Pattern TABLE_NAME = Pattern.compile("^[a-z][a-z_]{1,62}$");

    private TenantSchemaDefinition() {
    }

    public static List<String> createTableStatements() {
        List<String> statements = new ArrayList<>();
        statements.addAll(plainTables());
        statements.addAll(partitionFunctions());
        statements.add(copyFromLegacyFunction());
        statements.addAll(partitionedTables());
        statements.addAll(indexes());
        for (String table : TenantSchemaLayout.LEGACY.partitionedTablesDropOrder()) {
            statements.add(ensurePartitionsStatement(table, DEFAULT_MONTHS_AHEAD));
        }
        return statements;
    }

    /** Creates partitions from the current UTC month through {@code monthsAhead} months later. */
    public static String ensurePartitionsStatement(String table, int monthsAhead) {
        return "SELECT ensure_monthly_partitions('" + requireTableName(table) + "', "
                + "(now() AT TIME ZONE 'UTC')::date, "
                + "((now() AT TIME ZONE 'UTC') + interval '" + monthsAhead + " months')::date)";
    }

    /** Detaches and drops partitions whose month is older than {@code retentionMonths} before the current month. */
    public static String dropPartitionsStatement(String table, int retentionMonths) {
        return "SELECT drop_monthly_partitions_before('" + requireTableName(table) + "', "
                + "(date_trunc('month', now() AT TIME ZONE 'UTC') - interval '" + retentionMonths + " months')::date)";
    }

    private static String requireTableName(String table) {
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid partitioned table name: " + table);
        }
        return table;
    }

    private static List<String> plainTables() {
        return List.of(
                """
                        CREATE TABLE IF NOT EXISTS email_templates (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            name VARCHAR(255) NOT NULL,
                            subject VARCHAR(500) NOT NULL,
                            body TEXT NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """,
                """
                        CREATE TABLE IF NOT EXISTS sms_templates (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            name VARCHAR(255) NOT NULL,
                            message TEXT NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """,
                """
                        CREATE TABLE IF NOT EXISTS campaigns (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            name VARCHAR(255) NOT NULL,
                            channel VARCHAR(50) NOT NULL,
                            status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
                            scheduled_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """,
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
                            metric_name VARCHAR(100) NOT NULL,
                            metric_value BIGINT NOT NULL DEFAULT 0,
                            period_start TIMESTAMPTZ NOT NULL,
                            period_end TIMESTAMPTZ NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """,
                """
                        ALTER TABLE usage_counters
                        ADD COLUMN IF NOT EXISTS product_id UUID
                        """,
                // Merge duplicate counter rows (from the old non-atomic find-then-insert) before the
                // unique index that email-service's INSERT ... ON CONFLICT upsert relies on.
                """
                        DO $$
                        BEGIN
                            IF NOT EXISTS (
                                SELECT 1 FROM pg_indexes
                                WHERE schemaname = current_schema()
                                  AND indexname = 'uq_usage_counters_product_metric_period'
                            ) THEN
                                UPDATE usage_counters u
                                SET metric_value = d.total
                                FROM (
                                    SELECT MIN(id::text) AS keep_id, SUM(metric_value) AS total
                                    FROM usage_counters
                                    GROUP BY product_id, metric_name, period_start
                                    HAVING COUNT(*) > 1
                                ) d
                                WHERE u.id::text = d.keep_id;

                                DELETE FROM usage_counters u
                                WHERE EXISTS (
                                    SELECT 1 FROM usage_counters k
                                    WHERE k.product_id IS NOT DISTINCT FROM u.product_id
                                      AND k.metric_name = u.metric_name
                                      AND k.period_start = u.period_start
                                      AND k.id::text < u.id::text
                                );
                            END IF;
                        END $$;
                        """,
                """
                        CREATE UNIQUE INDEX IF NOT EXISTS uq_usage_counters_product_metric_period
                        ON usage_counters (product_id, metric_name, period_start)
                        """,
                """
                        DROP INDEX IF EXISTS idx_usage_counters_product_metric
                        """,
                """
                        ALTER TABLE email_templates
                        ADD COLUMN IF NOT EXISTS product_id UUID
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_email_templates_product
                        ON email_templates (product_id)
                        """,
                """
                        ALTER TABLE sms_templates
                        ADD COLUMN IF NOT EXISTS product_id UUID
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_sms_templates_product
                        ON sms_templates (product_id)
                        """,
                """
                        ALTER TABLE campaigns
                        ADD COLUMN IF NOT EXISTS product_id UUID
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_campaigns_product
                        ON campaigns (product_id)
                        """);
    }

    /**
     * Per-schema partition helpers, shared by both layouts. SET search_path
     * FROM CURRENT pins each function to the tenant schema it was created in,
     * whoever calls it.
     */
    static List<String> partitionFunctions() {
        return List.of(
                // Creates {table}_pYYYYMM for every month in [month of p_from, month of p_to].
                // Bounds are written with an explicit +00 so they never depend on the session TimeZone.
                """
                        CREATE OR REPLACE FUNCTION ensure_monthly_partitions(p_table text, p_from date, p_to date)
                        RETURNS integer
                        LANGUAGE plpgsql
                        SET search_path FROM CURRENT
                        AS $fn$
                        DECLARE
                            m date := date_trunc('month', p_from)::date;
                            part text;
                            created integer := 0;
                        BEGIN
                            WHILE m <= p_to LOOP
                                part := p_table || '_p' || to_char(m, 'YYYYMM');
                                IF to_regclass(part) IS NULL THEN
                                    EXECUTE format(
                                        'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                                        part, p_table,
                                        to_char(m, 'YYYY-MM-DD') || ' 00:00:00+00',
                                        to_char((m + interval '1 month')::date, 'YYYY-MM-DD') || ' 00:00:00+00');
                                    created := created + 1;
                                END IF;
                                m := (m + interval '1 month')::date;
                            END LOOP;
                            RETURN created;
                        END
                        $fn$
                        """,
                // Retention: detach then drop every {table}_pYYYYMM whose month is before p_before.
                // Detach (rather than a bare drop) is required for partitions referenced by a foreign key.
                """
                        CREATE OR REPLACE FUNCTION drop_monthly_partitions_before(p_table text, p_before date)
                        RETURNS integer
                        LANGUAGE plpgsql
                        SET search_path FROM CURRENT
                        AS $fn$
                        DECLARE
                            part record;
                            dropped integer := 0;
                        BEGIN
                            FOR part IN
                                SELECT c.relname
                                FROM pg_inherits i
                                JOIN pg_class c ON c.oid = i.inhrelid
                                WHERE i.inhparent = to_regclass(p_table)
                                  AND c.relname ~ ('^' || p_table || '_p[0-9]{6}$')
                                  AND to_date(right(c.relname, 6), 'YYYYMM') < date_trunc('month', p_before)::date
                                ORDER BY c.relname
                            LOOP
                                EXECUTE format('ALTER TABLE %I DETACH PARTITION %I', p_table, part.relname);
                                EXECUTE format('DROP TABLE %I', part.relname);
                                dropped := dropped + 1;
                            END LOOP;
                            RETURN dropped;
                        END
                        $fn$
                        """);
    }

    /**
     * Conversion helper for pre-partitioning legacy schemas: creates partitions
     * covering {table}_legacy's rows (by created_at) and copies the columns both
     * tables share. Caller drops the legacy table.
     */
    private static String copyFromLegacyFunction() {
        return """
                        CREATE OR REPLACE FUNCTION copy_from_legacy(p_table text)
                        RETURNS bigint
                        LANGUAGE plpgsql
                        SET search_path FROM CURRENT
                        AS $fn$
                        DECLARE
                            legacy text := p_table || '_legacy';
                            lo date;
                            cols text;
                            copied bigint;
                        BEGIN
                            EXECUTE format('SELECT (min(created_at) AT TIME ZONE ''UTC'')::date FROM %I', legacy) INTO lo;
                            IF lo IS NULL THEN
                                RETURN 0;
                            END IF;
                            PERFORM ensure_monthly_partitions(p_table, lo, (now() AT TIME ZONE 'UTC')::date);

                            SELECT string_agg(quote_ident(n.column_name), ', ' ORDER BY n.ordinal_position)
                            INTO cols
                            FROM information_schema.columns n
                            JOIN information_schema.columns o
                              ON o.table_schema = n.table_schema
                             AND o.table_name = legacy
                             AND o.column_name = n.column_name
                            WHERE n.table_schema = current_schema()
                              AND n.table_name = p_table;

                            EXECUTE format('INSERT INTO %I (%s) SELECT %s FROM %I', p_table, cols, cols, legacy);
                            GET DIAGNOSTICS copied = ROW_COUNT;
                            RETURN copied;
                        END
                        $fn$
                        """;
    }

    /**
     * Each block is atomic and handles three states: table missing (new tenant)
     * -> create partitioned; plain table (pre-partitioning tenant) -> rename to
     * *_legacy, create partitioned, copy, drop legacy; already partitioned -> no-op.
     */
    private static List<String> partitionedTables() {
        return List.of(
                // email_logs + email_log_recipients convert together because of the foreign key.
                // Recipients are partitioned by their parent log's created_at, so a send and all its
                // recipients always share a month (even when a send runs across a month boundary).
                """
                        DO $$
                        DECLARE
                            kind "char" := (SELECT relkind FROM pg_class WHERE oid = to_regclass('email_logs'));
                        BEGIN
                            IF kind = 'p' THEN
                                RETURN;
                            END IF;

                            IF kind IS NOT NULL THEN
                                ALTER TABLE email_logs RENAME TO email_logs_legacy;
                                IF to_regclass('email_log_recipients') IS NOT NULL THEN
                                    ALTER TABLE email_log_recipients RENAME TO email_log_recipients_legacy;
                                END IF;
                            END IF;

                            CREATE TABLE email_logs (
                                id UUID NOT NULL DEFAULT gen_random_uuid(),
                                product_id UUID,
                                subject VARCHAR(500) NOT NULL,
                                body TEXT,
                                status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                                recipient_count INTEGER NOT NULL DEFAULT 0,
                                success_count INTEGER NOT NULL DEFAULT 0,
                                failure_count INTEGER NOT NULL DEFAULT 0,
                                sent_at TIMESTAMPTZ,
                                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                CONSTRAINT pk_email_logs PRIMARY KEY (id, created_at)
                            ) PARTITION BY RANGE (created_at);

                            CREATE TABLE email_log_recipients (
                                id UUID NOT NULL DEFAULT gen_random_uuid(),
                                email_log_id UUID NOT NULL,
                                email_log_created_at TIMESTAMPTZ NOT NULL,
                                recipient VARCHAR(320) NOT NULL,
                                status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                                error_message TEXT,
                                sent_at TIMESTAMPTZ,
                                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                CONSTRAINT pk_email_log_recipients PRIMARY KEY (id, email_log_created_at),
                                CONSTRAINT fk_email_log_recipients_log FOREIGN KEY (email_log_id, email_log_created_at)
                                    REFERENCES email_logs (id, created_at) ON DELETE CASCADE
                            ) PARTITION BY RANGE (email_log_created_at);

                            IF kind IS NULL THEN
                                RETURN;
                            END IF;

                            PERFORM copy_from_legacy('email_logs');
                            PERFORM ensure_monthly_partitions('email_log_recipients',
                                (SELECT (min(created_at) AT TIME ZONE 'UTC')::date FROM email_logs_legacy),
                                (now() AT TIME ZONE 'UTC')::date);

                            -- Pre-restructure layout stored one email_logs row per recipient.
                            IF EXISTS (
                                SELECT 1 FROM information_schema.columns
                                WHERE table_schema = current_schema()
                                  AND table_name = 'email_logs_legacy'
                                  AND column_name = 'recipient'
                            ) THEN
                                EXECUTE 'INSERT INTO email_log_recipients '
                                     || '(email_log_id, email_log_created_at, recipient, status, sent_at, created_at) '
                                     || 'SELECT id, created_at, recipient, status, sent_at, created_at FROM email_logs_legacy';
                            END IF;

                            IF to_regclass('email_log_recipients_legacy') IS NOT NULL THEN
                                INSERT INTO email_log_recipients
                                    (id, email_log_id, email_log_created_at, recipient, status, error_message, sent_at, created_at)
                                SELECT r.id, r.email_log_id, l.created_at, r.recipient, r.status, r.error_message,
                                       r.sent_at, r.created_at
                                FROM email_log_recipients_legacy r
                                JOIN email_logs_legacy l ON l.id = r.email_log_id;
                                DROP TABLE email_log_recipients_legacy;
                            END IF;
                            DROP TABLE email_logs_legacy;

                            -- Backfill the denormalised counts for logs written before they existed.
                            UPDATE email_logs l
                            SET recipient_count = c.total, success_count = c.sent, failure_count = c.failed
                            FROM (
                                SELECT email_log_id, email_log_created_at,
                                       count(*) AS total,
                                       count(*) FILTER (WHERE status = 'SENT') AS sent,
                                       count(*) FILTER (WHERE status = 'FAILED') AS failed
                                FROM email_log_recipients
                                GROUP BY email_log_id, email_log_created_at
                            ) c
                            WHERE l.id = c.email_log_id AND l.created_at = c.email_log_created_at
                              AND l.recipient_count = 0;
                        END $$;
                        """,
                """
                        DO $$
                        DECLARE
                            kind "char" := (SELECT relkind FROM pg_class WHERE oid = to_regclass('sms_logs'));
                        BEGIN
                            IF kind = 'p' THEN
                                RETURN;
                            END IF;
                            IF kind IS NOT NULL THEN
                                ALTER TABLE sms_logs RENAME TO sms_logs_legacy;
                            END IF;

                            CREATE TABLE sms_logs (
                                id UUID NOT NULL DEFAULT gen_random_uuid(),
                                product_id UUID,
                                recipient VARCHAR(20) NOT NULL,
                                message TEXT NOT NULL,
                                status VARCHAR(50) NOT NULL,
                                sent_at TIMESTAMPTZ,
                                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                CONSTRAINT pk_sms_logs PRIMARY KEY (id, created_at)
                            ) PARTITION BY RANGE (created_at);

                            IF kind IS NOT NULL THEN
                                PERFORM copy_from_legacy('sms_logs');
                                DROP TABLE sms_logs_legacy;
                            END IF;
                        END $$;
                        """,
                """
                        DO $$
                        DECLARE
                            kind "char" := (SELECT relkind FROM pg_class WHERE oid = to_regclass('audit_logs'));
                        BEGIN
                            IF kind = 'p' THEN
                                RETURN;
                            END IF;
                            IF kind IS NOT NULL THEN
                                ALTER TABLE audit_logs RENAME TO audit_logs_legacy;
                            END IF;

                            CREATE TABLE audit_logs (
                                id UUID NOT NULL DEFAULT gen_random_uuid(),
                                product_id UUID,
                                actor_email VARCHAR(320),
                                action VARCHAR(255) NOT NULL,
                                entity_type VARCHAR(100),
                                entity_id VARCHAR(100),
                                details TEXT,
                                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                CONSTRAINT pk_audit_logs PRIMARY KEY (id, created_at)
                            ) PARTITION BY RANGE (created_at);

                            IF kind IS NOT NULL THEN
                                PERFORM copy_from_legacy('audit_logs');
                                DROP TABLE audit_logs_legacy;
                            END IF;
                        END $$;
                        """);
    }

    /** Declared on the partitioned parents, so every (future) partition gets them automatically. */
    private static List<String> indexes() {
        return List.of(
                // "This product's logs, newest first, in a date range" with keyset paging on (created_at, id).
                """
                        CREATE INDEX IF NOT EXISTS idx_email_logs_product_created
                        ON email_logs (product_id, created_at DESC, id DESC)
                        """,
                // Recipients of one log, paged by id; partial index for "failed recipients" lookups.
                """
                        CREATE INDEX IF NOT EXISTS idx_email_log_recipients_log_id
                        ON email_log_recipients (email_log_id, id)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_email_log_recipients_failed
                        ON email_log_recipients (email_log_id, id)
                        WHERE status = 'FAILED'
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_sms_logs_product_created
                        ON sms_logs (product_id, created_at DESC, id DESC)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_audit_logs_product_created
                        ON audit_logs (product_id, created_at DESC)
                        """);
    }
}
