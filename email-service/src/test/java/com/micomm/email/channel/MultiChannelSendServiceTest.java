package com.micomm.email.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.email.audit.EmailAuditLog;
import com.micomm.email.channel.dto.SendNotificationRequest;
import com.micomm.email.channel.dto.SendNotificationRequest.EmailContent;
import com.micomm.email.channel.dto.SendNotificationRequest.NotificationRecipient;
import com.micomm.email.channel.dto.SendNotificationRequest.TextContent;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.TenantResolutionException;
import com.micomm.email.client.dto.ResolvedProviderDto;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.kafka.ChannelSendMessage;
import com.micomm.email.kafka.EmailSendMessage;
import com.micomm.email.send.IdempotencyStore;

class MultiChannelSendServiceTest {

    private static final UUID CLUB = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, EmailSendMessage> emailKafka = mock(KafkaTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, ChannelSendMessage> channelKafka = mock(KafkaTemplate.class);
    private final TenantManagementClient tenants = mock(TenantManagementClient.class);
    private final TextMessageProviders textProviders = new TextMessageProviders(List.of(
            new TwilioSmsProvider(HttpClient.newHttpClient(), "http://unused", objectMapper),
            new TwilioWhatsAppProvider(HttpClient.newHttpClient(), "http://unused", objectMapper)));

    private final MultiChannelSendService service = new MultiChannelSendService(emailKafka, channelKafka,
            "email.send.v1", "notification.send.v1", 2, 900_000, objectMapper, mock(ClubAccessGuard.class), tenants,
            textProviders, mock(IdempotencyStore.class), mock(EmailAuditLog.class));

    @BeforeEach
    void setUp() {
        tenantOnLayout("6");
        provider("EMAIL", "SENDGRID", "{\"fromEmail\": \"club@x.com\"}");
        provider("SMS", "TWILIO", "{\"accountSid\": \"AC1\", \"fromNumber\": \"+15550001111\"}");
        provider("WHATSAPP", "TWILIO_WHATSAPP", "{\"accountSid\": \"AC1\", \"fromNumber\": \"+15550002222\"}");
        when(emailKafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channelKafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private void tenantOnLayout(String layout) {
        when(tenants.resolveTenantDatabase(eq(CLUB), eq(PRODUCT), isNull()))
                .thenReturn(new TenantDatabaseDto("tenant_x", layout, List.of(), "KCC", "MiClub"));
    }

    private void provider(String channel, String type, String config) {
        when(tenants.resolveProvider(CLUB, PRODUCT, channel, null))
                .thenReturn(new ResolvedProviderDto(UUID.randomUUID(), type, "secret", config));
    }

    private static SendNotificationRequest request(List<String> channels, List<NotificationRecipient> recipients,
            EmailContent email, TextContent sms) {
        return new SendNotificationRequest(CLUB, PRODUCT, channels, null, "campaign", "Regatta {{day}}",
                "Hi {{memberName}},\nboats <ready> at {{clubName}}", email, sms, null, Map.of("day", "Saturday"),
                recipients);
    }

    private static NotificationRecipient person(String email, String phone, String name) {
        return new NotificationRecipient(email, phone, null, Map.of("memberName", name));
    }

    @Test
    void sendsEachChannelToTheRecipientsWithAnAddressForIt() {
        List<NotificationRecipient> people = List.of(
                person("a@x.com", "+447700900001", "Ann"),
                person("b@x.com", null, "Bob"),
                person(null, "+447700900003", "Cy"));

        var response = service.submit(request(List.of("email", "SMS", "WHATSAPP"), people, null,
                new TextContent("{{memberName}}: regatta {{day}}")), null).response();

        assertThat(response.recipientCount()).isEqualTo(3);
        assertThat(response.channels()).containsExactly(Map.entry("EMAIL", 2), Map.entry("SMS", 2),
                Map.entry("WHATSAPP", 2));

        ArgumentCaptor<EmailSendMessage> emails = ArgumentCaptor.forClass(EmailSendMessage.class);
        verify(emailKafka, times(1)).send(eq("email.send.v1"), anyString(), emails.capture());
        EmailSendMessage email = emails.getValue();
        assertThat(email.requestId()).isEqualTo(response.requestId());
        assertThat(email.recipients()).containsExactly("a@x.com", "b@x.com");
        assertThat(email.fromEmail()).isEqualTo("club@x.com");
        assertThat(email.subject()).isEqualTo("Regatta {{day}}");
        // Plain body as HTML: escaped, line breaks kept, placeholders left for the worker.
        assertThat(email.htmlBody()).isEqualTo("Hi {{memberName}},<br>\nboats &lt;ready&gt; at {{clubName}}");
        assertThat(email.sendChannelCount()).isEqualTo(3);
        assertThat(email.notificationRecipients()).isEqualTo(3);
        assertThat(email.sendRecipientCount()).isEqualTo(2);
        assertThat(email.type()).isEqualTo("CAMPAIGN");
        assertThat(email.variablesFor(1)).containsEntry("memberName", "Bob").containsEntry("clubName", "KCC");

        ArgumentCaptor<ChannelSendMessage> texts = ArgumentCaptor.forClass(ChannelSendMessage.class);
        verify(channelKafka, times(2)).send(eq("notification.send.v1"), anyString(), texts.capture());
        ChannelSendMessage sms = texts.getAllValues().get(0);
        assertThat(sms.channel()).isEqualTo("SMS");
        assertThat(sms.text()).isEqualTo("{{memberName}}: regatta {{day}}");
        assertThat(sms.recipients()).containsExactly("+447700900001", "+447700900003");
        assertThat(sms.variablesFor(1)).containsEntry("memberName", "Cy");
        assertThat(sms.channelCount()).isEqualTo(3);
        assertThat(sms.notificationRecipientCount()).isEqualTo(3);
        ChannelSendMessage whatsApp = texts.getAllValues().get(1);
        // No override: title and body on separate lines; WhatsApp falls back to the phone number.
        assertThat(whatsApp.text()).isEqualTo("Regatta {{day}}\nHi {{memberName}},\nboats <ready> at {{clubName}}");
        assertThat(whatsApp.recipients()).containsExactly("+447700900001", "+447700900003");
    }

    @Test
    void largeChannelsAreChunked() {
        List<NotificationRecipient> people = List.of(person("a@x.com", null, "A"), person("b@x.com", null, "B"),
                person("c@x.com", null, "C"));

        service.submit(request(List.of("EMAIL"), people, new EmailContent(null, "S", "<p>{{memberName}}</p>"), null),
                null);

        ArgumentCaptor<EmailSendMessage> emails = ArgumentCaptor.forClass(EmailSendMessage.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(emailKafka, times(2)).send(eq("email.send.v1"), keys.capture(), emails.capture());
        assertThat(emails.getAllValues()).extracting(c -> c.recipients().size()).containsExactly(2, 1);
        assertThat(emails.getAllValues().get(1).variablesFor(0)).containsEntry("memberName", "C");
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void refusesWhatCannotBeSentBeforeQueueingAnything() {
        List<NotificationRecipient> people = List.of(person("a@x.com", null, "Ann"));

        assertThatThrownBy(() -> service.submit(request(List.of("SMS"), people, null, null), null))
                .hasMessageContaining("have no address on [SMS]");
        assertThatThrownBy(() -> service.submit(request(List.of("EMAIL", "SMS"), List.of(
                person("a@x.com", null, "Ann"), person("b@x.com", null, "Bob")), null, null), null))
                .hasMessageContaining("No recipient has an address for SMS");
        assertThatThrownBy(() -> service.submit(request(List.of("PUSH"), people, null, null), null))
                .hasMessageContaining("PUSH is not available");

        // A placeholder without a value, named per channel and recipient.
        NotificationRecipient noName = new NotificationRecipient("n@x.com", "+447700900009", null, null);
        assertThatThrownBy(() -> service.submit(request(List.of("SMS"), List.of(noName), null,
                new TextContent("Hi {{memberName}}")), null))
                .hasMessageContaining("Missing merge fields for 1 SMS recipient(s): +447700900009 (memberName)");

        verify(emailKafka, never()).send(anyString(), anyString(), any());
        verify(channelKafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    void channelsNeedAProviderOfTheirKind() {
        List<NotificationRecipient> people = List.of(person("a@x.com", "+447700900001", "Ann"));
        when(tenants.resolveProvider(CLUB, PRODUCT, "WHATSAPP", null))
                .thenThrow(new TenantResolutionException("No WHATSAPP provider configured"));

        assertThatThrownBy(() -> service.submit(request(List.of("EMAIL", "WHATSAPP"), people, null, null), null))
                .hasMessageContaining("No WHATSAPP provider is set up for this product");

        provider("SMS", "SENDGRID", "{}");
        assertThatThrownBy(() -> service.submit(request(List.of("SMS"), people, null, null), null))
                .hasMessageContaining("of type SENDGRID, which cannot send SMS");
        verify(emailKafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    void onlyClubsWithDeliveriesCanSendToSeveralChannels() {
        tenantOnLayout("4");

        assertThatThrownBy(() -> service.submit(request(List.of("EMAIL"), List.of(person("a@x.com", null, "A")),
                null, null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layout 4");
    }
}
