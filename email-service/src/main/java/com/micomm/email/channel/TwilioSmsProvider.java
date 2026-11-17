package com.micomm.email.channel;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class TwilioSmsProvider extends TwilioMessageProvider {

    public TwilioSmsProvider(HttpClient twilioHttpClient,
            @Value("${micomm.twilio.base-url:https://api.twilio.com}") String baseUrl, ObjectMapper objectMapper) {
        super(twilioHttpClient, baseUrl, objectMapper);
    }

    @Override
    public String providerTypeCode() {
        return "TWILIO";
    }

    @Override
    public String channel() {
        return "SMS";
    }

    @Override
    String address(String number) {
        return number;
    }
}
