package com.micomm.email.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.email.audit.EmailAuditLog;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.kafka.EmailSendMessage;
import com.micomm.email.send.dto.EmailRecipient;
import com.micomm.email.send.dto.SendBulkEmailRequest;
import com.micomm.email.template.EmailTemplateService;

class EmailSendChunkingTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, EmailSendMessage> kafka = mock(KafkaTemplate.class);
    private final TenantManagementClient tenants = mock(TenantManagementClient.class);

    private final EmailSendService service = new EmailSendService(kafka, "email.send.v1", 2, 900_000,
            new ObjectMapper().registerModule(new JavaTimeModule()), mock(ClubAccessGuard.class), tenants,
            mock(EmailTemplateService.class), mock(IdempotencyStore.class), mock(EmailAuditLog.class));

    @Test
    void splitsRecipientsIntoChunksThatShareTheRequestAndKeepTheirOwnValues() {
        UUID clubId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(tenants.resolveTenantDatabase(eq(clubId), eq(productId), isNull()))
                .thenReturn(new TenantDatabaseDto("club_x", "3", List.of(), "KCC", "MiClub"));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        List<EmailRecipient> recipients = IntStream.range(0, 5)
                .mapToObj(i -> new EmailRecipient("m" + i + "@x.com", Map.of("memberName", "M" + i)))
                .toList();
        var response = service.submit(new SendBulkEmailRequest(clubId, productId, "club@x.com",
                "Hi {{memberName}}", "<p>{{clubName}}</p>", null, null, null, null, recipients), null).response();

        ArgumentCaptor<EmailSendMessage> sent = ArgumentCaptor.forClass(EmailSendMessage.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(kafka, org.mockito.Mockito.times(3)).send(eq("email.send.v1"), keys.capture(), sent.capture());

        List<EmailSendMessage> chunks = sent.getAllValues();
        assertThat(response.recipientCount()).isEqualTo(5);
        assertThat(chunks).extracting(c -> c.recipients().size()).containsExactly(2, 2, 1);
        assertThat(chunks).extracting(EmailSendMessage::requestId).containsOnly(response.requestId());
        assertThat(chunks).extracting(EmailSendMessage::submittedAt).containsOnly(chunks.get(0).submittedAt());
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.chunkCount()).isEqualTo(3);
            assertThat(c.sendRecipientCount()).isEqualTo(5);
        });
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();

        EmailSendMessage last = chunks.get(2);
        assertThat(last.recipients()).containsExactly("m4@x.com");
        assertThat(last.variablesFor(0)).containsEntry("memberName", "M4").containsEntry("clubName", "KCC");
    }
}
