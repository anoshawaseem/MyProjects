package com.micomm.email.notification;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    /** List view: no bodies, so list queries never touch TOASTed content. */
    /** type: TRANSACTIONAL or CAMPAIGN (always TRANSACTIONAL for clubs before layout "5"). */
    public record NotificationSummaryDto(
            UUID id,
            UUID productId,
            String type,
            String channel,
            String sender,
            String subject,
            String status,
            int recipientCount,
            int successCount,
            int failureCount,
            OffsetDateTime sentAt,
            OffsetDateTime createdAt
    ) {}

    public record NotificationDetailDto(
            UUID id,
            UUID productId,
            String type,
            String channel,
            String sender,
            String subject,
            String body,
            String htmlBody,
            JsonNode channelData,
            String status,
            int recipientCount,
            int successCount,
            int failureCount,
            OffsetDateTime sentAt,
            OffsetDateTime createdAt
    ) {}

    public record NotificationRecipientDto(
            UUID id,
            String channel,
            String address,
            String status,
            String providerMessageId,
            String errorMessage,
            OffsetDateTime sentAt,
            OffsetDateTime deliveredAt,
            OffsetDateTime createdAt
    ) {}
}
