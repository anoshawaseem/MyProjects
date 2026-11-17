package com.micomm.email.send;

/**
 * The email queue (Kafka) could not be reached, so nothing of the send was queued: retrying
 * later, even with the same Idempotency-Key, is safe.
 */
public class QueueUnavailableException extends RuntimeException {

    public QueueUnavailableException(Throwable cause) {
        super("The email queue is unavailable, so nothing was sent. Try again shortly.", cause);
    }
}
