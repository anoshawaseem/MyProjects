package com.micomm.email.channel;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sends through Twilio's Messages API (POST /2010-04-01/Accounts/{sid}/Messages.json). The
 * provider account holds config {"accountSid", "fromNumber"} and the auth token as its secret.
 * fromNumber may also be a Messaging Service SID ("MG..."), which Twilio then picks a sender from.
 */
abstract class TwilioMessageProvider implements TextMessageProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    TwilioMessageProvider(HttpClient httpClient, String baseUrl, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.objectMapper = objectMapper;
    }

    /** How Twilio addresses a number on this channel (WhatsApp: "whatsapp:+44..."). */
    abstract String address(String number);

    @Override
    public String sender(String configJson) {
        return config(configJson).path("fromNumber").asText(null);
    }

    @Override
    public TextMessageResult send(TextMessageRequest request) {
        JsonNode config = config(request.configJson());
        String accountSid = config.path("accountSid").asText("");
        String from = config.path("fromNumber").asText("");
        if (accountSid.isBlank() || from.isBlank() || request.secretReference() == null
                || request.secretReference().isBlank()) {
            return TextMessageResult.failure("TWILIO_NOT_CONFIGURED",
                    "The Twilio account needs an Account SID, From Number and Auth Token");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", address(request.to()));
        if (from.startsWith("MG")) {
            form.put("MessagingServiceSid", from);
        } else {
            form.put("From", address(from));
        }
        form.put("Body", request.text());

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/2010-04-01/Accounts/"
                        + URLEncoder.encode(accountSid, StandardCharsets.UTF_8) + "/Messages.json"))
                .timeout(TIMEOUT)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (accountSid + ":" + request.secretReference()).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode body = parse(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return TextMessageResult.success(body.path("sid").asText(null));
            }
            // Twilio errors: {"code": 21211, "message": "The 'To' number ... is not a valid phone number."}
            String code = body.path("code").asText(String.valueOf(response.statusCode()));
            String message = body.path("message").asText(response.body());
            return TextMessageResult.failure("TWILIO_" + code, message);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return TextMessageResult.failure("TWILIO_SEND_FAILED", "Interrupted");
        } catch (IOException ex) {
            return TextMessageResult.failure("TWILIO_SEND_FAILED", ex.getMessage());
        }
    }

    private JsonNode config(String configJson) {
        return parse(configJson);
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (IOException ex) {
            return objectMapper.createObjectNode();
        }
    }

    private static String formEncode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
