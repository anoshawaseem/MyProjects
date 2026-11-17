package com.micomm.email.channel;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * WhatsApp through Twilio: the same Messages API with "whatsapp:"-prefixed numbers. Note that
 * WhatsApp only delivers free-form text within 24 hours of the member's last message to the
 * club (or in Twilio's sandbox); other messages need a template approved by Meta.
 */
@Component
public class TwilioWhatsAppProvider extends TwilioMessageProvider {

    private static final String PREFIX = "whatsapp:";

    public TwilioWhatsAppProvider(HttpClient twilioHttpClient,
            @Value("${micomm.twilio.base-url:https://api.twilio.com}") String baseUrl, ObjectMapper objectMapper) {
        super(twilioHttpClient, baseUrl, objectMapper);
    }

    @Override
    public String providerTypeCode() {
        return "TWILIO_WHATSAPP";
    }

    @Override
    public String channel() {
        return "WHATSAPP";
    }

    @Override
    String address(String number) {
        return number.startsWith(PREFIX) ? number : PREFIX + number;
    }
}
