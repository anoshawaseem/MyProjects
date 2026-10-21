package com.micomm.email.usage;

import static com.micomm.email.tenant.TenantTables.table;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.micomm.email.client.dto.TenantDatabaseDto;
import org.springframework.stereotype.Component;

/**
 * Writes email send results into a tenant schema with the legacy
 * per-channel layout (schemaVersion "1"); see NotificationWriter for the
 * notifications layout. usage_counters is identical in both layouts and is
 * written from here for both.
 * Schema name is validated upstream (comes from tenant-management-service,
 * which already validates it against ^[a-z][a-z0-9_]{1,62}$ on creation)
 * and again by {@link com.micomm.email.tenant.TenantTables}.
 *
 * Schema is shared across a club's products; rows are scoped via product_id:
 * email_logs(id, product_id, subject, body, status, recipient_count,
 * success_count, failure_count, sent_at, created_at) -- partitioned by month on created_at
 * email_log_recipients(id, email_log_id, email_log_created_at, recipient, status,
 * error_message, sent_at, created_at) -- partitioned by month on email_log_created_at
 * usage_counters(id, product_id, metric_name, metric_value, period_start,
 * period_end, created_at) -- unique on (product_id, metric_name, period_start)
 */
@Component
public class EmailMessageWriter {

    /** Rows per JDBC batch; pgjdbc rewrites each batch into multi-row INSERTs. */
    public static final int RECIPIENT_BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;

    public EmailMessageWriter(JdbcTemplate platformJdbcTemplate) {
        this.jdbcTemplate = platformJdbcTemplate;
    }

    public record RecipientResult(String recipient, boolean success, String errorMessage, Instant sentAt) {
    }

    /**
     * Identifies an email_logs row. email_logs is partitioned by created_at, so
     * the timestamp travels with the id: it routes recipient rows to the same
     * partition (email_log_created_at) and lets updates touch one partition.
     */
    public record EmailLogRef(UUID id, OffsetDateTime createdAt) {
    }

    /**
     * Starts (or joins) recording a send in the legacy email_logs layout. Every chunk of the
     * send passes the same id and createdAt (the send's requestId and submission time), so the
     * first chunk to arrive inserts the row and the others find it.
     */
    public SendRecord startEmailLog(String schemaName, UUID id, OffsetDateTime createdAt, UUID productId,
            String subject, String body, int recipientCount) {
        EmailLogRef log = new EmailLogRef(id, createdAt);
        insertEmailLog(schemaName, log, productId, subject, body, recipientCount);
        return new SendRecord() {
            @Override
            public void recordRecipients(List<RecipientResult> results) {
                insertRecipients(schemaName, log, results);
            }

            @Override
            public void addResults(int successCount, int failureCount) {
                addEmailLogResults(schemaName, log, successCount, failureCount);
            }
        };
    }

    /** Inserts the shared email content ONCE per bulk/single send, scoped to a product. */
    private void insertEmailLog(String schemaName, EmailLogRef log, UUID productId, String subject, String body,
            int recipientCount) {
        jdbcTemplate.update(
                "INSERT INTO " + table(schemaName, "email_logs")
                        + " (id, product_id, subject, body, status, recipient_count, created_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', ?, ?) ON CONFLICT DO NOTHING",
                log.id(), productId, subject, body, recipientCount, log.createdAt());
    }

    /**
     * Adds a chunk's counts and moves the status on, in one statement, so readers never need
     * COUNT(*) over email_log_recipients.
     */
    private void addEmailLogResults(String schemaName, EmailLogRef log, int successCount, int failureCount) {
        jdbcTemplate.update(
                "UPDATE " + table(schemaName, "email_logs") + " AS t SET " + SendRecord.ADD_RESULTS_SET
                        + " FROM (VALUES (?::int, ?::int)) AS d(s, f) WHERE t.id = ? AND t.created_at = ?",
                successCount, failureCount, log.id(), log.createdAt());
    }

    /**
     * Inserts recipient rows (already carrying their final status) in JDBC
     * batches instead of one round-trip per recipient.
     */
    public void insertRecipients(String schemaName, EmailLogRef log, List<RecipientResult> results) {
        if (results.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO " + table(schemaName, "email_log_recipients")
                        + " (id, email_log_id, email_log_created_at, recipient, status, error_message, sent_at,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
                results,
                RECIPIENT_BATCH_SIZE,
                (ps, r) -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, log.id());
                    ps.setObject(3, log.createdAt());
                    ps.setString(4, r.recipient());
                    ps.setString(5, r.success() ? "SENT" : "FAILED");
                    ps.setString(6, r.errorMessage());
                    ps.setTimestamp(7, r.success() && r.sentAt() != null ? Timestamp.from(r.sentAt()) : null);
                });
    }

    /**
     * Adds the given deltas to today's usage_counters rows for a club + product
     * in a single atomic upsert. Safe across concurrent workers/instances thanks
     * to the unique key on (product_id, metric_name, period_start), or
     * (club_id, product_id, metric_name, period_start) in club-scoped schemas.
     */
    public void incrementUsageCounters(TenantDatabaseDto tenant, UUID clubId, UUID productId,
            Map<String, Long> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        boolean clubScoped = tenant.clubScoped();

        OffsetDateTime periodStart = OffsetDateTime.now().truncatedTo(ChronoUnit.DAYS);
        Timestamp start = Timestamp.from(periodStart.toInstant());
        Timestamp end = Timestamp.from(periodStart.plusDays(1).toInstant());

        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(table(tenant.schemaName(), "usage_counters"))
                .append(" (id, ").append(clubScoped ? "club_id, " : "")
                .append("product_id, metric_name, metric_value, period_start, period_end, created_at) VALUES ");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Long> delta : deltas.entrySet()) {
            sql.append(first ? "" : ", ").append(clubScoped ? "(?, ?, ?, ?, ?, ?, ?, now())" : "(?, ?, ?, ?, ?, ?, now())");
            first = false;
            args.add(UUID.randomUUID());
            if (clubScoped) {
                args.add(clubId);
            }
            args.add(productId);
            args.add(delta.getKey());
            args.add(delta.getValue());
            args.add(start);
            args.add(end);
        }
        sql.append(clubScoped
                ? " ON CONFLICT (club_id, product_id, metric_name, period_start) "
                : " ON CONFLICT (product_id, metric_name, period_start) ");
        sql.append("DO UPDATE SET metric_value = usage_counters.metric_value + EXCLUDED.metric_value");

        jdbcTemplate.update(sql.toString(), args.toArray());
    }
}
