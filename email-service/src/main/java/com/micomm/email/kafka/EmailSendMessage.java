package com.micomm.email.kafka;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Kafka message published to "email.send.v1" when a bulk email request
 * is accepted by the public API. Consumed asynchronously by the worker,
 * which resolves the tenant schema + provider and performs the actual send.
 */
public record EmailSendMessage(
        UUID requestId,
        String idempotencyKey,
        UUID clubId,
        UUID productId,
        String fromEmail,
        String subject,
        String htmlBody,
        List<String> recipients,
        OffsetDateTime submittedAt
) {}