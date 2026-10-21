package com.micomm.email.notification;

import static com.micomm.email.tenant.TenantTables.table;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.usage.EmailMessageWriter;
import com.micomm.email.usage.EmailMessageWriter.RecipientResult;
import com.micomm.email.usage.SendRecord;

/**
 * Writes send results into a tenant schema with the notifications layout
 * (schemaVersion "2" or "3"):
 * notifications(id, [club_id,] product_id, channel, status, sender, subject,
 * body, html_body, ..., recipient_count, success_count, failure_count,
 * sent_at, created_at) -- partitioned by month on created_at
 * notification_recipients(id, notification_id, notification_created_at,
 * [club_id, product_id,] channel, address, status, ...) -- partitioned by
 * month on notification_created_at
 *
 * Club-scoped schemas ("3") also store club_id (and product_id on recipients);
 * the database rejects a club_id that is not the schema's own club.
 */
@Component
public class NotificationWriter {

    private final JdbcTemplate jdbcTemplate;

    public NotificationWriter(JdbcTemplate platformJdbcTemplate) {
        this.jdbcTemplate = platformJdbcTemplate;
    }

    /** Identifies a notifications row; created_at is its (and its recipients') partition key. */
    public record NotificationRef(UUID id, OffsetDateTime createdAt, String channel, UUID clubId, UUID productId) {
    }

    /**
     * Starts (or joins) recording one send. Every chunk of the send passes the same id and
     * createdAt (the send's requestId and submission time; createdAt is also the partition key),
     * so the first chunk to arrive inserts the row and the others find it. moduleCode
     * (club-scoped schemas only) and templateId record where the send came from.
     */
    public SendRecord startNotification(TenantDatabaseDto tenant, UUID id, OffsetDateTime createdAt, UUID clubId,
            UUID productId, String moduleCode, UUID templateId, String channel, String sender, String subject,
            String body, String htmlBody, int recipientCount) {
        String schemaName = tenant.schemaName();
        boolean clubScoped = tenant.clubScoped();

        List<Object> args = new ArrayList<>(List.of(id));
        if (clubScoped) {
            args.add(clubId);
            args.add(moduleCode);
        }
        args.addAll(java.util.Arrays.asList(productId, templateId, channel, sender, subject, body, htmlBody,
                recipientCount, createdAt));

        jdbcTemplate.update(
                "INSERT INTO " + table(schemaName, "notifications")
                        + " (id, " + (clubScoped ? "club_id, module_code, " : "")
                        + "product_id, template_id, channel, status, sender, subject, body, html_body,"
                        + " recipient_count, created_at)"
                        + " VALUES (?, " + (clubScoped ? "?, ?, " : "") + "?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT DO NOTHING",
                args.toArray());
        NotificationRef ref = new NotificationRef(id, createdAt, channel, clubId, productId);

        return new SendRecord() {
            @Override
            public void recordRecipients(List<RecipientResult> results) {
                insertRecipients(schemaName, clubScoped, ref, results);
            }

            @Override
            public void addResults(int successCount, int failureCount) {
                addNotificationResults(schemaName, clubScoped, ref, successCount, failureCount);
            }
        };
    }

    private void insertRecipients(String schemaName, boolean clubScoped, NotificationRef ref,
            List<RecipientResult> results) {
        if (results.isEmpty()) {
            return;
        }
        String scopeColumns = clubScoped ? "club_id, product_id, " : "";
        String scopeValues = clubScoped ? "?, ?, " : "";
        jdbcTemplate.batchUpdate(
                "INSERT INTO " + table(schemaName, "notification_recipients")
                        + " (id, notification_id, notification_created_at, " + scopeColumns
                        + "channel, address, status, error_message, sent_at, created_at)"
                        + " VALUES (?, ?, ?, " + scopeValues + "?, ?, ?, ?, ?, now())",
                results,
                EmailMessageWriter.RECIPIENT_BATCH_SIZE,
                (ps, r) -> {
                    int i = 1;
                    ps.setObject(i++, UUID.randomUUID());
                    ps.setObject(i++, ref.id());
                    ps.setObject(i++, ref.createdAt());
                    if (clubScoped) {
                        ps.setObject(i++, ref.clubId());
                        ps.setObject(i++, ref.productId());
                    }
                    ps.setString(i++, ref.channel());
                    ps.setString(i++, r.recipient());
                    ps.setString(i++, r.success() ? "SENT" : "FAILED");
                    ps.setString(i++, r.errorMessage());
                    ps.setTimestamp(i, r.success() && r.sentAt() != null ? Timestamp.from(r.sentAt()) : null);
                });
    }

    private void addNotificationResults(String schemaName, boolean clubScoped, NotificationRef ref,
            int successCount, int failureCount) {
        List<Object> args = new ArrayList<>(List.of(successCount, failureCount, ref.id(), ref.createdAt()));
        if (clubScoped) {
            args.add(ref.clubId());
            args.add(ref.productId());
        }
        jdbcTemplate.update(
                "UPDATE " + table(schemaName, "notifications") + " AS t SET " + SendRecord.ADD_RESULTS_SET
                        + " FROM (VALUES (?::int, ?::int)) AS d(s, f) WHERE t.id = ? AND t.created_at = ?"
                        + (clubScoped ? " AND t.club_id = ? AND t.product_id = ?" : ""),
                args.toArray());
    }
}
