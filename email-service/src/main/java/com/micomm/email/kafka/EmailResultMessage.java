package com.micomm.email.kafka;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Kafka message published to "email.result.v1" after processing completes
 * (fully or partially). Allows async status tracking / notifications.
 */
public record EmailResultMessage(
        UUID requestId,
        UUID clubId,
        UUID productId,
        int totalRecipients,
        int successCount,
        int failureCount,
        List<FailureDetail> failures,
        OffsetDateTime processedAt
) {
    public record FailureDetail(String recipient, String reason) {}
}