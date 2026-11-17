package com.micomm.email.notification;

import static com.micomm.email.tenant.TenantTables.table;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.usage.EmailMessageWriter;
import com.micomm.email.usage.EmailMessageWriter.RecipientResult;
import com.micomm.email.usage.SendRecord;

/**
 * Writes send results into a tenant schema with a notifications layout.
 *
 * Layouts "2" to "4": one notifications row per channel of a send:
 * notifications(id, [club_id,] [module_code,] product_id, channel, status, sender, subject,
 * body, html_body, ..., recipient_count, success_count, failure_count,
 * sent_at, created_at) -- partitioned by month on created_at
 * notification_recipients(id, notification_id, notification_created_at,
 * [club_id,] [product_id,] channel, address, status, ...) -- partitioned by
 * month on notification_created_at. Layout "3" also stores club_id; the
 * database rejects a club_id that is not the schema's own club.
 *
 * Layout "5": notifications(id, product_id, module_code, type, template_id, status,
 * recipient_count, sent_at, created_at) → notification_deliveries (one per channel:
 * content JSONB, sender, provider, status, counts) → notification_recipients
 * (delivery_id, address, status, ...), all partitioned by the send's created_at.
 *
 * Layout "8": the same, with template and subject per channel: notifications(id, product_id,
 * module_code, type, status, sent_at, created_at) → notification_deliveries(..., template_id,
 * global_template_id, subject, content without subject, ...).
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
     * (layouts "3" to "5") and templateId record where the send came from; type
     * (TRANSACTIONAL / CAMPAIGN) and providerAccountId are stored in layouts "5" and "6" only.
     *
     * recipientCount is this channel's recipients. Layouts "5" and "6" also take the whole send's
     * shape, as a multi-channel send writes one delivery per channel under the same notification:
     * notificationRecipientCount (people sent to) and channelCount (deliveries to expect).
     */
    public SendRecord startNotification(TenantDatabaseDto tenant, UUID id, OffsetDateTime createdAt, UUID clubId,
            UUID productId, String moduleCode, UUID templateId, String channel, String sender, String subject,
            String body, String htmlBody, int recipientCount, String type, UUID providerAccountId,
            int notificationRecipientCount, int channelCount) {
        if (tenant.usesDeliveries()) {
            return startDelivery(tenant.schemaName(), tenant.recipientsHaveProduct(), tenant.perChannelTemplates(),
                    id, createdAt, productId, moduleCode, templateId, channel, sender, subject, body, htmlBody,
                    recipientCount, type, providerAccountId, notificationRecipientCount, channelCount);
        }
        String schemaName = tenant.schemaName();
        Columns columns = Columns.of(tenant);

        List<Object> args = new ArrayList<>(List.of(id));
        if (columns.clubId()) {
            args.add(clubId);
        }
        if (columns.modules()) {
            args.add(moduleCode);
        }
        args.addAll(java.util.Arrays.asList(productId, templateId, channel, sender, subject, body, htmlBody,
                recipientCount, createdAt));

        jdbcTemplate.update(
                "INSERT INTO " + table(schemaName, "notifications")
                        + " (id, " + (columns.clubId() ? "club_id, " : "") + (columns.modules() ? "module_code, " : "")
                        + "product_id, template_id, channel, status, sender, subject, body, html_body,"
                        + " recipient_count, created_at)"
                        + " VALUES (?, " + (columns.clubId() ? "?, " : "") + (columns.modules() ? "?, " : "")
                        + "?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT DO NOTHING",
                args.toArray());
        NotificationRef ref = new NotificationRef(id, createdAt, channel, clubId, productId);

        return new SendRecord() {
            @Override
            public void recordRecipients(List<RecipientResult> results) {
                insertRecipients(schemaName, columns, ref, results);
            }

            @Override
            public void addResults(int successCount, int failureCount) {
                addNotificationResults(schemaName, columns, ref, successCount, failureCount);
            }
        };
    }

    /**
     * Layouts "5" to "8": joins the send's notifications row and its delivery for this channel,
     * creating them if this is the first chunk. The delivery id is derived from the send id and
     * channel, so every chunk of the send (processed in parallel) writes to the same delivery.
     * recipientsHaveProduct: layout "5" recipients also store product_id. perChannelTemplates:
     * layout "8" keeps the template and subject on the delivery, not the notification.
     */
    private SendRecord startDelivery(String schemaName, boolean recipientsHaveProduct, boolean perChannelTemplates,
            UUID id, OffsetDateTime createdAt, UUID productId,
            String moduleCode, UUID templateId, String channel, String sender, String subject, String body,
            String htmlBody, int recipientCount, String type, UUID providerAccountId, int notificationRecipientCount,
            int channelCount) {
        String sendType = type != null ? type : NotificationType.TRANSACTIONAL.name();
        UUID deliveryId = deliveryId(id, channel);
        if (perChannelTemplates) {
            jdbcTemplate.update("INSERT INTO " + table(schemaName, "notifications")
                    + " (id, product_id, module_code, type, status, created_at)"
                    + " VALUES (?, ?, ?, ?, 'PENDING', ?) ON CONFLICT DO NOTHING",
                    id, productId, moduleCode, sendType, createdAt);
            // A send's templateId is a module template or a global one; each has its own foreign key
            // column, so the id goes into whichever table it is in (neither if it was since deleted).
            jdbcTemplate.update("INSERT INTO " + table(schemaName, "notification_deliveries")
                    + " (id, notification_id, notification_created_at, product_id, channel, template_id,"
                    + " global_template_id, subject, provider_account_id, sender, content, status, recipient_count,"
                    + " created_at)"
                    + " VALUES (?, ?, ?, ?, ?,"
                    + " (SELECT t.id FROM " + table(schemaName, "notification_templates") + " t WHERE t.id = ?),"
                    + " (SELECT g.id FROM " + table(schemaName, "global_templates") + " g WHERE g.id = ?),"
                    + " ?, ?, ?, ?::jsonb, 'PENDING', ?, now()) ON CONFLICT DO NOTHING",
                    deliveryId, id, createdAt, productId, channel, templateId, templateId, subject,
                    providerAccountId, sender, content(null, body, htmlBody), recipientCount);
        } else {
            jdbcTemplate.update("INSERT INTO " + table(schemaName, "notifications")
                    + " (id, product_id, module_code, type, template_id, status, recipient_count, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?) ON CONFLICT DO NOTHING",
                    id, productId, moduleCode, sendType, templateId, notificationRecipientCount, createdAt);
            jdbcTemplate.update("INSERT INTO " + table(schemaName, "notification_deliveries")
                    + " (id, notification_id, notification_created_at, product_id, channel, provider_account_id, sender,"
                    + " content, status, recipient_count, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?, now()) ON CONFLICT DO NOTHING",
                    deliveryId, id, createdAt, productId, channel, providerAccountId, sender,
                    content(subject, body, htmlBody), recipientCount);
        }

        return new SendRecord() {
            @Override
            public void recordRecipients(List<RecipientResult> results) {
                if (results.isEmpty()) {
                    return;
                }
                jdbcTemplate.batchUpdate("INSERT INTO " + table(schemaName, "notification_recipients")
                        + " (id, delivery_id, notification_created_at, " + (recipientsHaveProduct ? "product_id, " : "")
                        + "address, status, error_message, sent_at)"
                        + " VALUES (?, ?, ?, " + (recipientsHaveProduct ? "?, " : "") + "?, ?, ?, ?)",
                        results, EmailMessageWriter.RECIPIENT_BATCH_SIZE, (ps, r) -> {
                            int i = 1;
                            ps.setObject(i++, UUID.randomUUID());
                            ps.setObject(i++, deliveryId);
                            ps.setObject(i++, createdAt);
                            if (recipientsHaveProduct) {
                                ps.setObject(i++, productId);
                            }
                            ps.setString(i++, r.recipient());
                            ps.setString(i++, r.success() ? "SENT" : "FAILED");
                            ps.setString(i++, r.errorMessage());
                            ps.setTimestamp(i, r.success() && r.sentAt() != null ? Timestamp.from(r.sentAt()) : null);
                        });
            }

            @Override
            public void addResults(int successCount, int failureCount) {
                jdbcTemplate.update("UPDATE " + table(schemaName, "notification_deliveries") + " AS t SET "
                        + SendRecord.ADD_RESULTS_SET
                        + " FROM (VALUES (?::int, ?::int)) AS d(s, f) WHERE t.id = ? AND t.notification_created_at = ?",
                        successCount, failureCount, deliveryId, createdAt);
                // The send's own status sums up its deliveries. Each chunk recomputes it after its own
                // update, so the last one to finish sees every delivery's final counts. A channel
                // whose delivery is not written yet keeps the send SENDING.
                jdbcTemplate.update("""
                        UPDATE %1$s AS n SET status = agg.status, sent_at = agg.sent_at
                        FROM (SELECT CASE
                                     WHEN count(*) < ? OR bool_or(status IN ('PENDING', 'SENDING')) THEN 'SENDING'
                                     WHEN bool_and(status = 'SENT') THEN 'SENT'
                                     WHEN bool_and(status = 'FAILED') THEN 'FAILED'
                                     ELSE 'PARTIAL' END AS status,
                                     max(sent_at) AS sent_at
                              FROM %2$s WHERE notification_id = ? AND notification_created_at = ?) AS agg
                        WHERE n.id = ? AND n.created_at = ?
                        """.formatted(table(schemaName, "notifications"), table(schemaName, "notification_deliveries")),
                        Math.max(channelCount, 1), id, createdAt, id, createdAt);
            }
        };
    }

    /** Same send + channel → same delivery id, whichever chunk asks first. */
    static UUID deliveryId(UUID notificationId, String channel) {
        return UUID.nameUUIDFromBytes((notificationId + ":" + channel).getBytes(StandardCharsets.UTF_8));
    }

    /** A delivery's channel-specific content; EMAIL: {"subject", "html", "text"}, SMS / WHATSAPP: {"text"}. */
    private static String content(String subject, String body, String htmlBody) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (subject != null) {
            node.put("subject", subject);
        }
        if (htmlBody != null) {
            node.put("html", htmlBody);
        }
        if (body != null) {
            node.put("text", body);
        }
        return node.toString();
    }

    /**
     * Which optional columns a schema's notification tables have: club_id (layout "3" only), and
     * module_code on notifications plus product_id on recipients (layouts "3" and "4").
     */
    private record Columns(boolean clubId, boolean modules) {
        static Columns of(TenantDatabaseDto tenant) {
            return new Columns(tenant.hasClubIdColumn(), tenant.supportsModules());
        }
    }

    private void insertRecipients(String schemaName, Columns columns, NotificationRef ref,
            List<RecipientResult> results) {
        if (results.isEmpty()) {
            return;
        }
        String scopeColumns = (columns.clubId() ? "club_id, " : "") + (columns.modules() ? "product_id, " : "");
        String scopeValues = (columns.clubId() ? "?, " : "") + (columns.modules() ? "?, " : "");
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
                    if (columns.clubId()) {
                        ps.setObject(i++, ref.clubId());
                    }
                    if (columns.modules()) {
                        ps.setObject(i++, ref.productId());
                    }
                    ps.setString(i++, ref.channel());
                    ps.setString(i++, r.recipient());
                    ps.setString(i++, r.success() ? "SENT" : "FAILED");
                    ps.setString(i++, r.errorMessage());
                    ps.setTimestamp(i, r.success() && r.sentAt() != null ? Timestamp.from(r.sentAt()) : null);
                });
    }

    private void addNotificationResults(String schemaName, Columns columns, NotificationRef ref,
            int successCount, int failureCount) {
        List<Object> args = new ArrayList<>(List.of(successCount, failureCount, ref.id(), ref.createdAt()));
        if (columns.clubId()) {
            args.add(ref.clubId());
        }
        args.add(ref.productId());
        jdbcTemplate.update(
                "UPDATE " + table(schemaName, "notifications") + " AS t SET " + SendRecord.ADD_RESULTS_SET
                        + " FROM (VALUES (?::int, ?::int)) AS d(s, f) WHERE t.id = ? AND t.created_at = ?"
                        + (columns.clubId() ? " AND t.club_id = ?" : "") + " AND t.product_id = ?",
                args.toArray());
    }
}
