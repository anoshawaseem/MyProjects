package com.micomm.email.usage;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes email send results into the dynamically-resolved tenant schema.
 * Schema name is validated upstream (comes from tenant-management-service,
 * which already validates it against ^[a-z][a-z0-9_]{1,62}$ on creation).
 *
 * Matches the actual tenant table layout created by
 * TenantSchemaDefinition.createTableStatements() in tenant-management-service:
 *   email_logs(id, recipient, subject, status, sent_at, created_at)
 *   usage_counters(id, metric_name, metric_value, period_start, period_end, created_at)
 */
@Component
public class EmailMessageWriter {

    private final JdbcTemplate jdbcTemplate;

    public EmailMessageWriter(JdbcTemplate platformJdbcTemplate) {
        this.jdbcTemplate = platformJdbcTemplate;
    }

    public UUID insertEmailLog(String schemaName, String recipientEmail, String subject, String status) {
        setSearchPath(schemaName);

        UUID logId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO email_logs (id, recipient, subject, status, sent_at, created_at) " +
                "VALUES (?, ?, ?, ?, ?, now())",
                logId, recipientEmail, subject, status,
                "SENT".equals(status) ? java.sql.Timestamp.from(java.time.Instant.now()) : null
        );
        return logId;
    }

    public void updateEmailLogStatus(String schemaName, UUID logId, String status) {
        setSearchPath(schemaName);
        jdbcTemplate.update(
                "UPDATE email_logs SET status = ?, sent_at = CASE WHEN ? = 'SENT' THEN now() ELSE sent_at END WHERE id = ?",
                status, status, logId
        );
    }

    /**
     * Increments usage_counters for the current day's period (or you could
     * use month-based bucketing - adjust as needed). Since there's no unique
     * constraint on (metric_name, period_start, period_end), this does a
     * find-or-insert-then-update to avoid duplicate rows from concurrent sends.
     */
    public synchronized void incrementUsageCounter(String schemaName, String metricName, long delta) {
        setSearchPath(schemaName);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime periodStart = now.truncatedTo(ChronoUnit.DAYS);
        OffsetDateTime periodEnd = periodStart.plusDays(1);

        Long existingId = jdbcTemplate.query(
                "SELECT id FROM usage_counters WHERE metric_name = ? AND period_start = ? AND period_end = ? LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null,
                metricName, java.sql.Timestamp.from(periodStart.toInstant()), java.sql.Timestamp.from(periodEnd.toInstant())
        );

        int updated = jdbcTemplate.update(
                "UPDATE usage_counters SET metric_value = metric_value + ? " +
                "WHERE metric_name = ? AND period_start = ? AND period_end = ?",
                delta, metricName,
                java.sql.Timestamp.from(periodStart.toInstant()), java.sql.Timestamp.from(periodEnd.toInstant())
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO usage_counters (id, metric_name, metric_value, period_start, period_end, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, now())",
                    UUID.randomUUID(), metricName, delta,
                    java.sql.Timestamp.from(periodStart.toInstant()), java.sql.Timestamp.from(periodEnd.toInstant())
            );
        }
    }

    private void setSearchPath(String schemaName) {
        if (!schemaName.matches("^[a-z][a-z0-9_]{1,62}$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
        jdbcTemplate.execute("SET search_path TO \"" + schemaName + "\"");
    }
}