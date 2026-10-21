package com.micomm.email.send;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.micomm.email.send.dto.SendBulkEmailRequest;
import com.micomm.email.send.dto.SendBulkEmailResponse;

/** Rules for the send endpoint's Idempotency-Key header. */
public final class Idempotency {

    public static final String HEADER = "Idempotency-Key";

    /** Printable ASCII without spaces, e.g. a UUID or "order-1234-reminder". */
    private static final Pattern VALID_KEY = Pattern.compile("^[\\x21-\\x7E]{1,255}$");

    private Idempotency() {
    }

    /** Null when no key was sent; rejects keys that are blank, too long or not printable ASCII. */
    static String normalizeKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (!VALID_KEY.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "'" + HEADER + "' must be 1-255 printable ASCII characters without spaces");
        }
        return trimmed;
    }

    /**
     * SHA-256 of the request as canonical JSON (map entries sorted), so the same request always
     * hashes the same however its JSON was ordered.
     */
    static String requestHash(ObjectMapper objectMapper, SendBulkEmailRequest request) {
        try {
            byte[] json = objectMapper.copy()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot hash send request", ex);
        }
    }

    /** The answer for a request whose key was already used. */
    static SendBulkEmailResponse replay(IdempotencyStore.Entry earlier, String requestHash) {
        if (!earlier.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        return switch (earlier.state()) {
            case COMPLETED -> new SendBulkEmailResponse(earlier.requestId(), "QUEUED", earlier.recipientCount());
            case IN_PROGRESS -> throw new IdempotencyConflictException("A request with this " + HEADER
                    + " is still being processed (send " + earlier.requestId() + "). Retry in a few seconds; if it"
                    + " persists, check that send's status before sending again with a new key.");
            case FAILED -> throw new IdempotencyConflictException("An earlier request with this " + HEADER
                    + " was only partly queued (send " + earlier.requestId() + "). Check that send's recipients"
                    + " before sending the rest with a new key.");
        };
    }

    /** 422: the key was used for a different request. */
    public static class IdempotencyKeyReusedException extends RuntimeException {
        IdempotencyKeyReusedException() {
            super("This " + HEADER + " was already used for a different request; use a new key for a new send");
        }
    }

    /** 409: the key's earlier request is unfinished or failed part-way. */
    public static class IdempotencyConflictException extends RuntimeException {
        IdempotencyConflictException(String message) {
            super(message);
        }
    }
}
