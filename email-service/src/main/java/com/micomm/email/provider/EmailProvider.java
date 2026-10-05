package com.micomm.email.provider;

import java.util.List;

public interface EmailProvider {

    /**
     * Sends a single email to one recipient. Called once per recipient so
     * individual failures (bounces, invalid addresses) don't abort the
     * whole batch.
     */
    EmailSendResult send(EmailSendRequest request);

    String providerTypeCode();
}