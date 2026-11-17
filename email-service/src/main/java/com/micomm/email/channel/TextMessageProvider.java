package com.micomm.email.channel;

/**
 * Sends one text message (SMS or WhatsApp) through a provider account. Implementations are
 * picked by the account's provider type code (e.g. TWILIO, TWILIO_WHATSAPP).
 */
public interface TextMessageProvider {

    String providerTypeCode();

    /** The channel this provider sends on: SMS or WHATSAPP. */
    String channel();

    /** The sender recorded on the delivery (e.g. the account's from number), or null. */
    String sender(String configJson);

    /** Never throws for a failed send; the failure is in the result. */
    TextMessageResult send(TextMessageRequest request);

    /** to: the recipient's E.164 number; secretReference / configJson: the provider account's. */
    record TextMessageRequest(String to, String text, String secretReference, String configJson) {
    }

    record TextMessageResult(boolean success, String providerMessageId, String errorCode, String errorMessage) {

        public static TextMessageResult success(String providerMessageId) {
            return new TextMessageResult(true, providerMessageId, null, null);
        }

        public static TextMessageResult failure(String errorCode, String errorMessage) {
            return new TextMessageResult(false, null, errorCode, errorMessage);
        }
    }
}
