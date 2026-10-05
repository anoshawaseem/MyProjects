package com.micomm.email.usage;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes email send results into the dynamically-resolved tenant schema.
 * Schema name is validated upstream (comes from tenant-management-service,
 * which already validates it against ^[a-z][a-z0-9_]{1,62}$ on creation).
 */
@Component
public class EmailMessageWriter {

    private final JdbcTemplate jdbcTemplate;

    public EmailMessageWriter(JdbcTemplate platformJdbcTemplate) {
        this.jdbcTemplate = platformJdbcTemplate;
    }

    public UUID insertEmailMessage(String schemaName, UUID productId, UUID providerAccountId,
                                     String fromEmail, String subject, String htmlBody,
                                     int recipientCount, String idempotencyKey) {
        setSearchPath(schemaName);

        UUID messageId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO email.email_messages " +
                "(id, product_id, provider_account_id, from_email, subject, html_body, status, recipient_count, idempotency_key, created_at, queued_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, now(), now())",
                messageId, productId, providerAccountId, fromEmail, subject, htmlBody, recipientCount, idempotencyKey
        );
        return messageId;
    }

    public UUID insertRecipient(String schemaName, UUID emailMessageId, String emailAddress) {
        setSearchPath(schemaName);

        UUID recipientId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO email.email_recipients (id, email_message_id, email_address, recipient_type, status, created_at) " +
                "VALUES (?, ?, ?, 'TO', 'PENDING', now())",
                recipientId, emailMessageId, emailAddress
        );
        return recipientId;
    }

    public void markRecipientSent(String schemaName, UUID recipientId, String providerMessageId) {
        setSearchPath(schemaName);
        jdbcTemplate.update(
                "UPDATE email.email_recipients SET status = 'SENT', provider_message_id = ?, sent_at = now() WHERE id = ?",
                providerMessageId, recipientId
        );
    }

    public void markRecipientFailed(String schemaName, UUID recipientId, String errorCode, String errorMessage) {
        setSearchPath(schemaName);
        jdbcTemplate.update(
                "UPDATE email.email_recipients SET status = 'FAILED', error_code = ?, error_message = ?, failed_at = now() WHERE id = ?",
                errorCode, errorMessage, recipientId
        );
    }

    public void insertDeliveryAttempt(String schemaName, UUID recipientId, int attemptNumber,
                                        UUID providerAccountId, String status,
                                        String responseCode, String providerMessageId,
                                        String errorCode, String errorMessage) {
        setSearchPath(schemaName);
        jdbcTemplate.update(
                "INSERT INTO email.email_delivery_attempts " +
                "(email_recipient_id, attempt_number, provider_account_id, status, provider_response_code, provider_message_id, error_code, error_message, attempted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())",
                recipientId, attemptNumber, providerAccountId, status, responseCode, providerMessageId, errorCode, errorMessage
        );
    }

    public void markMessageCompleted(String schemaName, UUID emailMessageId, String finalStatus) {
        setSearchPath(schemaName);
        jdbcTemplate.update(
                "UPDATE email.email_messages SET status = ?, completed_at = now(), " +
                "sent_at = CASE WHEN sent_at IS NULL THEN now() ELSE sent_at END WHERE id = ?",
                finalStatus, emailMessageId
        );
    }

    /**
     * Upserts the usage row for (product, EMAIL, provider, current month),
     * incrementing counters atomically to avoid race conditions under
     * concurrent bulk sends.
     */
    public void incrementUsage(String schemaName, UUID productId, UUID providerAccountId,
                                 int messageDelta, int recipientDelta, int successDelta, int failureDelta) {
        setSearchPath(schemaName);

        LocalDate usagePeriod = LocalDate.now().withDayOfMonth(1);

        jdbcTemplate.update(
                "INSERT INTO usage.notification_usage " +
                "(product_id, channel, provider_account_id, usage_period, message_count, recipient_count, success_count, failure_count, updated_at) " +
                "VALUES (?, 'EMAIL', ?, ?, ?, ?, ?, ?, now()) " +
                "ON CONFLICT (product_id, channel, provider_account_id, usage_period) " +
                "DO UPDATE SET " +
                "  message_count = usage.notification_usage.message_count + EXCLUDED.message_count, " +
                "  recipient_count = usage.notification_usage.recipient_count + EXCLUDED.recipient_count, " +
                "  success_count = usage.notification_usage.success_count + EXCLUDED.success_count, " +
                "  failure_count = usage.notification_usage.failure_count + EXCLUDED.failure_count, " +
                "  updated_at = now()",
                productId, providerAccountId, Timestamp.valueOf(usagePeriod.atStartOfDay()),
                messageDelta, recipientDelta, successDelta, failureDelta
        );
    }

    private void setSearchPath(String schemaName) {
        // schemaName already validated at provisioning time (matches ^[a-z][a-z0-9_]{1,62}$),
        // but defensively re-check here before interpolating into SQL.
        if (!schemaName.matches("^[a-z][a-z0-9_]{1,62}$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
        jdbcTemplate.execute("SET search_path TO \"" + schemaName + "\"");
    }
}