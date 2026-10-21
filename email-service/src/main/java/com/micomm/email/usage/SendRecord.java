package com.micomm.email.usage;

import java.util.List;

import com.micomm.email.usage.EmailMessageWriter.RecipientResult;

/**
 * Handle to the row recording one send, independent of the tenant's table
 * layout (email_logs for legacy schemas, notifications for new ones).
 *
 * A large send is split into chunks that workers process in parallel; every
 * chunk gets a handle to the same row (its id is the send's requestId) and
 * adds its own results.
 */
public interface SendRecord {

    /** Appends recipient results; call repeatedly with batches. */
    void recordRecipients(List<RecipientResult> results);

    /**
     * Adds one chunk's counts. Atomic across concurrent chunks; once every recipient of the
     * send is counted the row gets its final status (SENT, PARTIAL or FAILED), until then SENDING.
     */
    void addResults(int successCount, int failureCount);

    /**
     * SET clause shared by both layouts' tables ({@code t} aliases the table, {@code d(s, f)} the
     * chunk's counts); Postgres evaluates every expression against the row as it was before.
     */
    String ADD_RESULTS_SET = """
            success_count = t.success_count + d.s,
            failure_count = t.failure_count + d.f,
            status = CASE
                WHEN t.success_count + d.s + t.failure_count + d.f < t.recipient_count THEN 'SENDING'
                WHEN t.failure_count + d.f = 0 THEN 'SENT'
                WHEN t.success_count + d.s = 0 THEN 'FAILED'
                ELSE 'PARTIAL' END,
            sent_at = CASE
                WHEN t.success_count + d.s + t.failure_count + d.f >= t.recipient_count
                     AND t.success_count + d.s > 0 THEN now()
                ELSE t.sent_at END
            """;
}
