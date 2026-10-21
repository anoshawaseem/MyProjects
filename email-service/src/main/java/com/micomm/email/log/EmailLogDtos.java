package com.micomm.email.log;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class EmailLogDtos {

    private EmailLogDtos() {
    }

    /** List view: no body, so list queries never touch TOASTed content. */
    public record EmailLogSummaryDto(
            UUID id,
            UUID productId,
            String subject,
            String status,
            int recipientCount,
            int successCount,
            int failureCount,
            OffsetDateTime sentAt,
            OffsetDateTime createdAt
    ) {}

    public record EmailLogDetailDto(
            UUID id,
            UUID productId,
            String subject,
            String body,
            String status,
            int recipientCount,
            int successCount,
            int failureCount,
            OffsetDateTime sentAt,
            OffsetDateTime createdAt
    ) {}

    public record EmailLogRecipientDto(
            UUID id,
            String recipient,
            String status,
            String errorMessage,
            OffsetDateTime sentAt,
            OffsetDateTime createdAt
    ) {}
}
