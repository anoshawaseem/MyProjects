package com.micomm.email.provider;

public record EmailSendResult(
        boolean success,
        String providerMessageId,
        String responseCode,
        String errorCode,
        String errorMessage
) {
    public static EmailSendResult success(String providerMessageId, String responseCode) {
        return new EmailSendResult(true, providerMessageId, responseCode, null, null);
    }

    public static EmailSendResult failure(String errorCode, String errorMessage) {
        return new EmailSendResult(false, null, null, errorCode, errorMessage);
    }
}