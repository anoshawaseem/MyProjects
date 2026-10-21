package com.micomm.email.kafka;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Kafka message published to "email.result.v1" after processing completes
 * (fully or partially). Allows async status tracking / notifications.
 *
 * One message per chunk of a send: totalRecipients / successCount /
 * failureCount are the chunk's; sendRecipientCount is the whole send's, so a
 * consumer knows the send is done when the chunks' counts add up to it.
 */
public record EmailResultMessage(
        UUID requestId,
        UUID clubId,
        UUID productId,
        int totalRecipients,
        int successCount,
        int failureCount,
        List<FailureDetail> failures,
        OffsetDateTime processedAt,
        int chunkIndex,
        int chunkCount,
        int sendRecipientCount
) {
    public record FailureDetail(String recipient, String reason) {}
}
