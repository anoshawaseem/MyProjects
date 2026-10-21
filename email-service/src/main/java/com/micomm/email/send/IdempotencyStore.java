package com.micomm.email.send;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Remembers send requests by the caller's Idempotency-Key, per club, so a retried request is
 * answered with the original send instead of emailing everyone again.
 *
 * Lives in email-service's own "email_service" schema of the platform database (club schemas
 * are all "tenant_*", so it cannot collide with one), created on startup if missing. Records
 * expire after {@code micomm.email.idempotency-ttl}; the same key can then start a new send.
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    public enum State { IN_PROGRESS, COMPLETED, FAILED }

    /** A key already seen for this club. recipientCount is set once the send was queued. */
    public record Entry(String requestHash, State state, UUID requestId, Integer recipientCount) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final Duration ttl;

    public IdempotencyStore(JdbcTemplate platformJdbcTemplate,
            @Value("${micomm.email.idempotency-ttl:24h}") Duration ttl) {
        this.jdbcTemplate = platformJdbcTemplate;
        this.ttl = ttl;
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS email_service");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS email_service.send_idempotency (
                    club_id         UUID NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    request_hash    CHAR(64) NOT NULL,
                    state           VARCHAR(20) NOT NULL
                                    CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
                    request_id      UUID,
                    recipient_count INTEGER,
                    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (club_id, idempotency_key)
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_send_idempotency_created"
                + " ON email_service.send_idempotency (created_at)");
    }

    /**
     * Claims the key for a new send with the given requestId. Returns empty if the claim
     * succeeded (the caller must then {@link #complete}, {@link #fail} or {@link #release} it), or
     * the existing entry if the key was already used within the TTL. An expired entry is replaced.
     */
    public Optional<Entry> claim(UUID clubId, String key, String requestHash, UUID requestId) {
        // An expired record no longer protects anything; free the key for this request.
        jdbcTemplate.update("DELETE FROM email_service.send_idempotency WHERE club_id = ? AND idempotency_key = ?"
                + " AND created_at < now() - make_interval(secs => ?::double precision)",
                clubId, key, ttl.toSeconds());
        int inserted = jdbcTemplate.update("""
                INSERT INTO email_service.send_idempotency (club_id, idempotency_key, request_hash, state, request_id)
                VALUES (?, ?, ?, 'IN_PROGRESS', ?)
                ON CONFLICT (club_id, idempotency_key) DO NOTHING
                """, clubId, key, requestHash, requestId);
        if (inserted == 1) {
            return Optional.empty();
        }
        List<Entry> existing = jdbcTemplate.query("""
                SELECT request_hash, state, request_id, recipient_count
                FROM email_service.send_idempotency WHERE club_id = ? AND idempotency_key = ?
                """, (rs, n) -> new Entry(rs.getString("request_hash"), State.valueOf(rs.getString("state")),
                rs.getObject("request_id", UUID.class), (Integer) rs.getObject("recipient_count")), clubId, key);
        if (existing.isEmpty()) {
            // Released by a failing request between our insert and select; claim it now.
            return claim(clubId, key, requestHash, requestId);
        }
        return Optional.of(existing.get(0));
    }

    /** The send was queued: later requests with this key get this answer. */
    public void complete(UUID clubId, String key, int recipientCount) {
        setState(clubId, key, State.COMPLETED, recipientCount);
    }

    /**
     * Queueing failed part-way: some batches may already be sending, so the key stays taken and
     * points at the send; repeating it would risk emailing those recipients twice.
     */
    public void fail(UUID clubId, String key, int recipientCount) {
        setState(clubId, key, State.FAILED, recipientCount);
    }

    private void setState(UUID clubId, String key, State state, int recipientCount) {
        jdbcTemplate.update("UPDATE email_service.send_idempotency SET state = ?, recipient_count = ?,"
                + " updated_at = now() WHERE club_id = ? AND idempotency_key = ?",
                state.name(), recipientCount, clubId, key);
    }

    /** Nothing was queued (e.g. the request was invalid): the key can be used again right away. */
    public void release(UUID clubId, String key) {
        jdbcTemplate.update("DELETE FROM email_service.send_idempotency WHERE club_id = ? AND idempotency_key = ?"
                + " AND state = 'IN_PROGRESS'", clubId, key);
    }

    // ISO-8601 duration (PT1H) or milliseconds: @Scheduled does not accept "1h".
    @Scheduled(fixedDelayString = "${micomm.email.idempotency-cleanup-interval:PT1H}")
    void deleteExpired() {
        int deleted = jdbcTemplate.update("DELETE FROM email_service.send_idempotency"
                + " WHERE created_at < now() - make_interval(secs => ?::double precision)", ttl.toSeconds());
        if (deleted > 0) {
            log.info("Deleted {} expired idempotency records", deleted);
        }
    }
}
