package com.micomm.email.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.email.channel.TextMessageProvider.TextMessageRequest;
import com.micomm.email.channel.TextMessageProvider.TextMessageResult;
import com.sun.net.httpserver.HttpServer;

/** Runs the Twilio senders against a local stand-in for Twilio's Messages API. */
class TwilioMessageProviderTest {

    private static final String CONFIG = "{\"accountSid\": \"AC123\", \"fromNumber\": \"+15550001111\"}";

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> auths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private int status = 201;
    private String reply = "{\"sid\": \"SM42\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            auths.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void smsPostsToTheAccountsMessagesWithBasicAuth() {
        TwilioSmsProvider sms = new TwilioSmsProvider(HttpClient.newHttpClient(), baseUrl(), new ObjectMapper());

        TextMessageResult result = sms.send(new TextMessageRequest("+447700900123", "Hi Ann & co", "token", CONFIG));

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("SM42");
        assertThat(paths).containsExactly("/2010-04-01/Accounts/AC123/Messages.json");
        assertThat(auths.get(0)).isEqualTo("Basic " + java.util.Base64.getEncoder()
                .encodeToString("AC123:token".getBytes(StandardCharsets.UTF_8)));
        assertThat(bodies.get(0)).isEqualTo("To=+447700900123&From=+15550001111&Body=Hi Ann & co");
        assertThat(sms.sender(CONFIG)).isEqualTo("+15550001111");
    }

    @Test
    void whatsAppPrefixesBothNumbers() {
        TwilioWhatsAppProvider whatsApp = new TwilioWhatsAppProvider(HttpClient.newHttpClient(), baseUrl(),
                new ObjectMapper());

        assertThat(whatsApp.send(new TextMessageRequest("+447700900123", "Hi", "token", CONFIG)).success()).isTrue();
        assertThat(bodies.get(0)).isEqualTo("To=whatsapp:+447700900123&From=whatsapp:+15550001111&Body=Hi");
    }

    @Test
    void messagingServiceSidIsSentAsSuch() {
        TwilioSmsProvider sms = new TwilioSmsProvider(HttpClient.newHttpClient(), baseUrl(), new ObjectMapper());

        sms.send(new TextMessageRequest("+447700900123", "Hi", "token",
                "{\"accountSid\": \"AC123\", \"fromNumber\": \"MG999\"}"));
        assertThat(bodies.get(0)).isEqualTo("To=+447700900123&MessagingServiceSid=MG999&Body=Hi");
    }

    @Test
    void twilioErrorsBecomeFailedResults() {
        status = 400;
        reply = "{\"code\": 21211, \"message\": \"The 'To' number is not a valid phone number.\"}";
        TwilioSmsProvider sms = new TwilioSmsProvider(HttpClient.newHttpClient(), baseUrl(), new ObjectMapper());

        TextMessageResult result = sms.send(new TextMessageRequest("+10", "Hi", "token", CONFIG));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TWILIO_21211");
        assertThat(result.errorMessage()).contains("not a valid phone number");
    }

    @Test
    void incompleteAccountsFailWithoutCallingTwilio() {
        TwilioSmsProvider sms = new TwilioSmsProvider(HttpClient.newHttpClient(), baseUrl(), new ObjectMapper());

        TextMessageResult result = sms.send(new TextMessageRequest("+447700900123", "Hi", "", CONFIG));

        assertThat(result.errorCode()).isEqualTo("TWILIO_NOT_CONFIGURED");
        assertThat(paths).isEmpty();
    }
}
