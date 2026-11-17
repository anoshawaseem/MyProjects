package com.micomm.email.notification;

import static com.micomm.email.tenant.TenantTables.table;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.notification.NotificationDtos.NotificationDetailDto;
import com.micomm.email.notification.NotificationDtos.NotificationRecipientDto;
import com.micomm.email.notification.NotificationDtos.NotificationSummaryDto;

/**
 * Product-scoped reads over the notifications layouts ("2" to "8"). Results are one row per
 * channel of a send. Layouts "2" to "4" store exactly that in notifications; layouts "5" on store a
 * send once in notifications and its channels in notification_deliveries, so reads join the two
 * into the same shape (see {@link #source}). Lists prune partitions via the created_at range;
 * recipient reads prune via notification_created_at.
 */
@Component
public class NotificationRepository {

    private static final String SUMMARY_COLUMNS = "id, product_id, type, channel, sender, subject, status, "
            + "recipient_count, success_count, failure_count, sent_at, created_at";

    private static final RowMapper<NotificationSummaryDto> SUMMARY_MAPPER = (rs, rowNum) -> new NotificationSummaryDto(
            rs.getObject("id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getString("type"),
            rs.getString("channel"),
            rs.getString("sender"),
            rs.getString("subject"),
            rs.getString("status"),
            rs.getInt("recipient_count"),
            rs.getInt("success_count"),
            rs.getInt("failure_count"),
            toOffset(rs, "sent_at"),
            toOffset(rs, "created_at"));

    private static final RowMapper<NotificationRecipientDto> RECIPIENT_MAPPER =
            (rs, rowNum) -> new NotificationRecipientDto(
                    rs.getObject("id", UUID.class),
                    rs.getString("channel"),
                    rs.getString("address"),
                    rs.getString("status"),
                    rs.getString("provider_message_id"),
                    rs.getString("error_message"),
                    toOffset(rs, "sent_at"),
                    toOffset(rs, "delivered_at"),
                    toOffset(rs, "created_at"));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public NotificationRepository(JdbcTemplate platformJdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = platformJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * What a notification read selects from, as a relation named "notifications" with the columns
     * of layouts "2" to "4" plus type. Layout "5" joins each send with its channel deliveries; the
     * older layouts have no type column, so their sends are TRANSACTIONAL.
     */
    private static String source(TenantDatabaseDto tenant) {
        String schema = tenant.schemaName();
        if (!tenant.usesDeliveries()) {
            return "(SELECT *, 'TRANSACTIONAL' AS type FROM " + table(schema, "notifications") + ") AS notifications";
        }
        // Layout "8" keeps the subject in its own column; "5" to "7" inside content.
        return "(SELECT n.id, n.product_id, n.module_code, n.type, d.channel, d.sender,"
                + (tenant.perChannelTemplates() ? " d.subject," : " d.content->>'subject' AS subject,")
                + " d.content->>'text' AS body, d.content->>'html' AS html_body,"
                + " d.content::text AS channel_data, d.status, d.recipient_count, d.success_count, d.failure_count,"
                + " d.sent_at, n.created_at"
                + " FROM " + table(schema, "notifications") + " n"
                + " JOIN " + table(schema, "notification_deliveries") + " d"
                + " ON d.notification_id = n.id AND d.notification_created_at = n.created_at) AS notifications";
    }

    /**
     * "WHERE" condition every statement starts with: product_id, plus club_id in layout "3"
     * schemas, matching their (club_id, product_id, ...) indexes.
     */
    private static String scope(TenantDatabaseDto tenant, UUID clubId, UUID productId, List<Object> args) {
        if (tenant.hasClubIdColumn()) {
            args.add(clubId);
            args.add(productId);
            return " WHERE club_id = ? AND product_id = ?";
        }
        args.add(productId);
        return " WHERE product_id = ?";
    }

    /**
     * Newest-first notifications for a club + product in [from, to), optionally
     * one channel and / or a subject containing {@code subject} (case-insensitive).
     * When afterCreatedAt/afterId are given, returns rows strictly after that keyset position.
     */
    public List<NotificationSummaryDto> findSummaries(TenantDatabaseDto tenant, UUID clubId, UUID productId,
            String moduleCode, String channel, String subject, Instant from, Instant to, Instant afterCreatedAt,
            UUID afterId, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + SUMMARY_COLUMNS + " FROM "
                + source(tenant) + scope(tenant, clubId, productId, args));
        // Module-aware schemas only (the caller checks).
        if (moduleCode != null) {
            sql.append(" AND module_code = ?");
            args.add(moduleCode);
        }
        if (channel != null) {
            sql.append(" AND channel = ?");
            args.add(channel);
        }
        if (subject != null) {
            // No subject index: the created_at range keeps the scan to the months searched.
            sql.append(" AND subject ILIKE ? ESCAPE '\\'");
            args.add(subjectContains(subject));
        }
        sql.append(" AND created_at >= ? AND created_at < ?");
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        if (afterCreatedAt != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            args.add(Timestamp.from(afterCreatedAt));
            args.add(afterId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), SUMMARY_MAPPER, args.toArray());
    }

    public Optional<NotificationDetailDto> findDetail(TenantDatabaseDto tenant, UUID clubId, UUID productId, UUID id,
            String channel) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + SUMMARY_COLUMNS
                + ", body, html_body, channel_data::text AS channel_data FROM "
                + source(tenant) + scope(tenant, clubId, productId, args) + " AND id = ?");
        args.add(id);
        if (channel != null) {
            sql.append(" AND channel = ?");
            args.add(channel);
        }
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new NotificationDetailDto(
                rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getString("type"),
                rs.getString("channel"),
                rs.getString("sender"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("html_body"),
                parseJson(rs.getString("channel_data")),
                rs.getString("status"),
                rs.getInt("recipient_count"),
                rs.getInt("success_count"),
                rs.getInt("failure_count"),
                toOffset(rs, "sent_at"),
                toOffset(rs, "created_at")), args.toArray()).stream().findFirst();
    }

    /**
     * Returns the notification's created_at if it exists, belongs to the
     * product (and channel, when given). It is the recipients' partition key,
     * so it prunes the recipient query to one partition.
     */
    public Optional<OffsetDateTime> findCreatedAt(TenantDatabaseDto tenant, UUID clubId, UUID productId, UUID id,
            String channel) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT created_at FROM " + source(tenant)
                + scope(tenant, clubId, productId, args) + " AND id = ?");
        args.add(id);
        if (channel != null) {
            sql.append(" AND channel = ?");
            args.add(channel);
        }
        return jdbcTemplate.queryForList(sql.toString(), OffsetDateTime.class, args.toArray()).stream().findFirst();
    }

    /** Recipients of one notification ordered by id; failedOnly uses the partial failed-recipients index. */
    public List<NotificationRecipientDto> findRecipients(TenantDatabaseDto tenant, UUID clubId, UUID productId,
            UUID notificationId, OffsetDateTime notificationCreatedAt, boolean failedOnly, UUID afterId, int limit) {
        if (tenant.usesDeliveries()) {
            return findDeliveryRecipients(tenant, productId, notificationId, notificationCreatedAt, failedOnly,
                    afterId, limit);
        }
        List<Object> args = new ArrayList<>(List.of(notificationId, notificationCreatedAt));
        StringBuilder sql = new StringBuilder(
                "SELECT id, channel, address, status, provider_message_id, error_message, sent_at, delivered_at,"
                        + " created_at FROM " + table(tenant.schemaName(), "notification_recipients")
                        + " WHERE notification_id = ? AND notification_created_at = ?");
        // Layout "2" recipients have no club/product columns; they are scoped through the parent
        // notification (which the caller has already matched to the product).
        if (tenant.hasClubIdColumn()) {
            sql.append(" AND club_id = ?");
            args.add(clubId);
        }
        if (tenant.supportsModules()) {
            sql.append(" AND product_id = ?");
            args.add(productId);
        }
        if (failedOnly) {
            sql.append(" AND status = 'FAILED'");
        }
        if (afterId != null) {
            sql.append(" AND id > ?");
            args.add(afterId);
        }
        sql.append(" ORDER BY id LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), RECIPIENT_MAPPER, args.toArray());
    }

    /**
     * Layouts "5" and "6": recipients of every delivery (channel) of the send, ordered by id. The
     * product is checked on the delivery (layout "6" recipients have no product_id); layout "6"
     * recipients have no created_at either, so the send's time stands in for it.
     */
    private List<NotificationRecipientDto> findDeliveryRecipients(TenantDatabaseDto tenant, UUID productId,
            UUID notificationId, OffsetDateTime notificationCreatedAt, boolean failedOnly, UUID afterId, int limit) {
        List<Object> args = new ArrayList<>(List.of(notificationId, notificationCreatedAt, productId));
        StringBuilder sql = new StringBuilder("SELECT r.id, d.channel, r.address, r.status, r.provider_message_id,"
                + " r.error_message, r.sent_at, r.delivered_at,"
                + (tenant.recipientsHaveProduct() ? " r.created_at" : " r.notification_created_at AS created_at")
                + " FROM " + table(tenant.schemaName(), "notification_recipients") + " r"
                + " JOIN " + table(tenant.schemaName(), "notification_deliveries") + " d"
                + " ON d.id = r.delivery_id AND d.notification_created_at = r.notification_created_at"
                + " WHERE d.notification_id = ? AND r.notification_created_at = ? AND d.product_id = ?");
        if (failedOnly) {
            sql.append(" AND r.status = 'FAILED'");
        }
        if (afterId != null) {
            sql.append(" AND r.id > ?");
            args.add(afterId);
        }
        sql.append(" ORDER BY r.id LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), RECIPIENT_MAPPER, args.toArray());
    }

    /** LIKE pattern matching {@code text} anywhere, with its own %, _ and \ taken literally. */
    public static String subjectContains(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private JsonNode parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid channel_data JSON", ex);
        }
    }

    private static OffsetDateTime toOffset(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
