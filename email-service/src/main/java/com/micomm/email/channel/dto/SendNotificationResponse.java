package com.micomm.email.channel.dto;

import java.util.Map;
import java.util.UUID;

/**
 * requestId is the notification's id (GET /api/v1/notifications/{requestId}). recipientCount is
 * the people sent to; channels the recipients per channel, e.g. {"EMAIL": 120, "SMS": 95}
 * (null when replaying an earlier answer for the same Idempotency-Key).
 */
public record SendNotificationResponse(UUID requestId, String status, int recipientCount,
        Map<String, Integer> channels) {
}
