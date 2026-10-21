package com.micomm.email.send;

import java.util.UUID;

/**
 * Kafka did not acknowledge every batch of a send in time. Batches that were acknowledged are
 * still delivered, so the caller must check the send before trying again.
 */
public class SendNotQueuedException extends RuntimeException {

    public SendNotQueuedException(UUID requestId, Throwable cause) {
        super("The send could not be fully queued (send " + requestId + "). Some recipients may still receive it;"
                + " check that send's status before trying again.", cause);
    }
}
