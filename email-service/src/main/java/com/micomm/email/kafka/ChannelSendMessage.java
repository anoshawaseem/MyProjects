package com.micomm.email.kafka;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One chunk of a text channel (SMS or WHATSAPP) of a multi-channel send, published to
 * "notification.send.v1". The send's EMAIL part goes out as {@link EmailSendMessage}s with the
 * same requestId; every channel is recorded as a delivery of the same notification.
 *
 * text still contains {{placeholders}}; the worker fills them per recipient from
 * {@code variables} (MiComm's own fields plus the send-level values) overridden by the
 * recipient's entry in {@code recipientVariables} (same order as {@code recipients}; null when
 * no recipient has values of their own). The service checked every value is there before
 * publishing.
 *
 * totalRecipients is this channel's recipients over all chunks; notificationRecipientCount the
 * people the whole send went to; channelCount how many channels it has.
 */
public record ChannelSendMessage(
        UUID requestId,
        UUID clubId,
        UUID productId,
        String channel,
        String moduleCode,
        String type,
        String text,
        List<String> recipients,
        Map<String, String> variables,
        List<Map<String, String>> recipientVariables,
        OffsetDateTime submittedAt,
        int chunkIndex,
        int chunkCount,
        int totalRecipients,
        int channelCount,
        int notificationRecipientCount
) {

    /** The merge values for the recipient at {@code index}: send-level values overridden by theirs. */
    public Map<String, String> variablesFor(int index) {
        Map<String, String> own = recipientVariables != null ? recipientVariables.get(index) : null;
        if (own == null || own.isEmpty()) {
            return variables;
        }
        Map<String, String> merged = new HashMap<>(variables);
        merged.putAll(own);
        return merged;
    }
}
