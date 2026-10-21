package com.micomm.email.log;

import static com.micomm.email.tenant.TenantTables.table;

import com.micomm.email.log.EmailLogDtos.EmailLogDetailDto;
import com.micomm.email.log.EmailLogDtos.EmailLogRecipientDto;
import com.micomm.email.log.EmailLogDtos.EmailLogSummaryDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

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

/**
 * Product-scoped reads over a club's tenant schema. Every query filters on
 * product_id and is shaped to hit idx_email_logs_product_created /
 * idx_email_log_recipients_log_id with keyset (not OFFSET) paging.
 *
 * Both tables are partitioned by month: list queries prune via the
 * created_at range, recipient queries via email_log_created_at.
 */
@Component
public class EmailLogRepository {

    private static final RowMapper<EmailLogSummaryDto> SUMMARY_MAPPER = (rs, rowNum) -> new EmailLogSummaryDto(
            rs.getObject("id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getString("subject"),
            rs.getString("status"),
            rs.getInt("recipient_count"),
            rs.getInt("success_count"),
            rs.getInt("failure_count"),
            toOffset(rs, "sent_at"),
            toOffset(rs, "created_at"));

    private static final RowMapper<EmailLogDetailDto> DETAIL_MAPPER = (rs, rowNum) -> new EmailLogDetailDto(
            rs.getObject("id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getString("subject"),
            rs.getString("body"),
            rs.getString("status"),
            rs.getInt("recipient_count"),
            rs.getInt("success_count"),
            rs.getInt("failure_count"),
            toOffset(rs, "sent_at"),
            toOffset(rs, "created_at"));

    private static final RowMapper<EmailLogRecipientDto> RECIPIENT_MAPPER = (rs, rowNum) -> new EmailLogRecipientDto(
            rs.getObject("id", UUID.class),
            rs.getString("recipient"),
            rs.getString("status"),
            rs.getString("error_message"),
            toOffset(rs, "sent_at"),
            toOffset(rs, "created_at"));

    private final JdbcTemplate jdbcTemplate;

    public EmailLogRepository(JdbcTemplate platformJdbcTemplate) {
        this.jdbcTemplate = platformJdbcTemplate;
    }

    /**
     * Newest-first logs for a product in [from, to). When afterCreatedAt/afterId
     * are given, returns rows strictly after that keyset position.
     */
    public List<EmailLogSummaryDto> findSummaries(String schemaName, UUID productId, Instant from, Instant to,
            Instant afterCreatedAt, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, product_id, subject, status, recipient_count, success_count, failure_count, "
                        + "sent_at, created_at FROM " + table(schemaName, "email_logs")
                        + " WHERE product_id = ? AND created_at >= ? AND created_at < ?");
        List<Object> args = new ArrayList<>(List.of(productId, Timestamp.from(from), Timestamp.from(to)));
        if (afterCreatedAt != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            args.add(Timestamp.from(afterCreatedAt));
            args.add(afterId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), SUMMARY_MAPPER, args.toArray());
    }

    public Optional<EmailLogDetailDto> findDetail(String schemaName, UUID productId, UUID logId) {
        return jdbcTemplate.query(
                "SELECT id, product_id, subject, body, status, recipient_count, success_count, failure_count, "
                        + "sent_at, created_at FROM " + table(schemaName, "email_logs")
                        + " WHERE id = ? AND product_id = ?",
                DETAIL_MAPPER, logId, productId).stream().findFirst();
    }

    /**
     * Returns the log's created_at if it exists and belongs to the product.
     * Recipients are partitioned by that timestamp, so it is used to prune
     * the recipient query to a single partition.
     */
    public Optional<OffsetDateTime> findLogCreatedAt(String schemaName, UUID productId, UUID logId) {
        return jdbcTemplate.queryForList(
                "SELECT created_at FROM " + table(schemaName, "email_logs") + " WHERE id = ? AND product_id = ?",
                OffsetDateTime.class, logId, productId).stream().findFirst();
    }

    /** Recipients of one log ordered by id; failedOnly uses the partial failed-recipients index. */
    public List<EmailLogRecipientDto> findRecipients(String schemaName, UUID logId, OffsetDateTime logCreatedAt,
            boolean failedOnly, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, recipient, status, error_message, sent_at, created_at FROM "
                        + table(schemaName, "email_log_recipients")
                        + " WHERE email_log_id = ? AND email_log_created_at = ?");
        List<Object> args = new ArrayList<>(List.of(logId, logCreatedAt));
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

    private static OffsetDateTime toOffset(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant().atOffset(ZoneOffset.UTC) : null;
    }
}
