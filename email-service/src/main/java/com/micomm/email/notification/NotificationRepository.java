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
 * Product-scoped reads over the notifications layout (schemaVersion "2").
 * Lists prune partitions via the created_at range and use
 * idx_notifications_product_created, or idx_notifications_product_channel_created
 * when a channel is given; recipient reads prune via notification_created_at.
 */
@Component
public class NotificationRepository {

    private static final String SUMMARY_COLUMNS = "id, product_id, channel, sender, subject, status, "
            + "recipient_count, success_count, failure_count, sent_at, created_at";

    private static final RowMapper<NotificationSummaryDto> SUMMARY_MAPPER = (rs, rowNum) -> new NotificationSummaryDto(
            rs.getObject("id", UUID.class),
            rs.getObject("product_id", UUID.class),
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
     * "WHERE" condition every statement starts with: product_id, plus club_id in
     * club-scoped schemas (layout "3"), matching their (club_id, product_id, ...) indexes.
     */
    private static String scope(TenantDatabaseDto tenant, UUID clubId, UUID productId, List<Object> args) {
        if (tenant.clubScoped()) {
            args.add(clubId);
            args.add(productId);
            return " WHERE club_id = ? AND product_id = ?";
        }
        args.add(productId);
        return " WHERE product_id = ?";
    }

    /**
     * Newest-first notifications for a club + product in [from, to), optionally
     * one channel. When afterCreatedAt/afterId are given, returns rows strictly
     * after that keyset position.
     */
    public List<NotificationSummaryDto> findSummaries(TenantDatabaseDto tenant, UUID clubId, UUID productId,
            String moduleCode, String channel, Instant from, Instant to, Instant afterCreatedAt, UUID afterId,
            int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + SUMMARY_COLUMNS + " FROM "
                + table(tenant.schemaName(), "notifications") + scope(tenant, clubId, productId, args));
        // Club-scoped schemas only (the caller checks); uses idx_notifications_scope_module_created.
        if (moduleCode != null) {
            sql.append(" AND module_code = ?");
            args.add(moduleCode);
        }
        if (channel != null) {
            sql.append(" AND channel = ?");
            args.add(channel);
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
        StringBuilder sql = new StringBuilder("SELECT " + SUMMARY_COLUMNS + ", body, html_body, channel_data FROM "
                + table(tenant.schemaName(), "notifications") + scope(tenant, clubId, productId, args)
                + " AND id = ?");
        args.add(id);
        if (channel != null) {
            sql.append(" AND channel = ?");
            args.add(channel);
        }
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new NotificationDetailDto(
                rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class),
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
        StringBuilder sql = new StringBuilder("SELECT created_at FROM " + table(tenant.schemaName(), "notifications")
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
        List<Object> args = new ArrayList<>(List.of(notificationId, notificationCreatedAt));
        StringBuilder sql = new StringBuilder(
                "SELECT id, channel, address, status, provider_message_id, error_message, sent_at, delivered_at,"
                        + " created_at FROM " + table(tenant.schemaName(), "notification_recipients")
                        + " WHERE notification_id = ? AND notification_created_at = ?");
        // Layout "2" recipients have no club/product columns; they are scoped through the parent
        // notification (which the caller has already matched to the product).
        if (tenant.clubScoped()) {
            sql.append(" AND club_id = ? AND product_id = ?");
            args.add(clubId);
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
