package com.micomm.email.kafka;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka message published to "email.send.v1" when a bulk email request
 * is accepted by the public API. Consumed asynchronously by the worker,
 * which resolves the tenant schema + provider and performs the actual send.
 *
 * moduleCode and templateId record where the email came from; both are null
 * for direct sends and in messages published before they were added.
 *
 * Merge fields: when {@code variables} is null, subject / htmlBody are final
 * and sent as-is (direct sends without variables, and messages published
 * before merge fields). Otherwise they still contain {{placeholders}}, and the
 * worker fills them per recipient from {@code variables} (MiComm's own fields
 * plus the send-level values, already validated) overridden by that
 * recipient's entry in {@code recipientVariables} (same order as
 * {@code recipients}; null when no recipient has values of their own).
 *
 * Chunks: a large send is published as {@code chunkCount} messages with the
 * same requestId, each carrying part of the recipients, so several workers
 * send it in parallel. {@code totalRecipients} is the whole send's count.
 * Messages published before chunking have chunkCount 0: one chunk holding
 * every recipient.
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
        OffsetDateTime submittedAt,
        String moduleCode,
        UUID templateId,
        Map<String, String> variables,
        List<Map<String, String>> recipientVariables,
        int chunkIndex,
        int chunkCount,
        int totalRecipients
) {

    /** Recipients of the whole send (older messages: the one chunk's). */
    public int sendRecipientCount() {
        return chunkCount > 0 ? totalRecipients : recipients.size();
    }

    /** True when subject / htmlBody contain placeholders the worker must fill per recipient. */
    public boolean merges() {
        return variables != null;
    }

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
