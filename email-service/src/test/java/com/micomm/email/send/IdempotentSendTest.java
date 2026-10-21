package com.micomm.email.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.micomm.common.audit.AuditActions;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.email.audit.EmailAuditLog;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.kafka.EmailSendMessage;
import com.micomm.email.send.IdempotencyStore.Entry;
import com.micomm.email.send.IdempotencyStore.State;
import com.micomm.email.send.dto.EmailRecipient;
import com.micomm.email.send.dto.SendBulkEmailRequest;
import com.micomm.email.template.EmailTemplateService;

class IdempotentSendTest {

    private static final UUID CLUB = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, EmailSendMessage> kafka = mock(KafkaTemplate.class);
    private final TenantManagementClient tenants = mock(TenantManagementClient.class);
    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final EmailAuditLog auditLog = mock(EmailAuditLog.class);
    private final EmailSendService service = new EmailSendService(kafka, "email.send.v1", 500, 900_000,
            objectMapper, mock(ClubAccessGuard.class), tenants, mock(EmailTemplateService.class), store, auditLog);

    @BeforeEach
    void tenant() {
        when(tenants.resolveTenantDatabase(eq(CLUB), eq(PRODUCT), isNull()))
                .thenReturn(new TenantDatabaseDto("tenant_x", "3", List.of(), "KCC", "MiClub"));
    }

    @Test
    void sameRequestHashesTheSameWhateverTheOrderOfItsValues() {
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, String> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");

        assertThat(Idempotency.requestHash(objectMapper, request(ab)))
                .isEqualTo(Idempotency.requestHash(objectMapper, request(ba)))
                .isNotEqualTo(Idempotency.requestHash(objectMapper, request(Map.of("a", "1"))));
    }

    @Test
    void keysMustBePrintableAsciiWithoutSpaces() {
        assertThat(Idempotency.normalizeKey(null)).isNull();
        assertThat(Idempotency.normalizeKey(" order-42 ")).isEqualTo("order-42");
        assertThatThrownBy(() -> Idempotency.normalizeKey("two words")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Idempotency.normalizeKey("x".repeat(256))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstRequestIsQueuedAndRecorded() {
        when(store.claim(eq(CLUB), eq("k1"), anyString(), any())).thenReturn(Optional.empty());
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        EmailSendService.Submission result = service.submit(request(Map.of()), "k1");

        assertThat(result.replayed()).isFalse();
        verify(store).complete(CLUB, "k1", 1);
    }

    @Test
    void repeatReturnsTheOriginalSendWithoutSendingAgain() {
        SendBulkEmailRequest request = request(Map.of());
        UUID original = UUID.randomUUID();
        when(store.claim(eq(CLUB), eq("k1"), anyString(), any())).thenReturn(Optional.of(
                new Entry(Idempotency.requestHash(objectMapper, request), State.COMPLETED, original, 1)));

        EmailSendService.Submission result = service.submit(request, "k1");

        assertThat(result.replayed()).isTrue();
        assertThat(result.response().requestId()).isEqualTo(original);
        verify(kafka, never()).send(anyString(), anyString(), any());
        // The replay is in the audit log, marked as such.
        verify(auditLog).record(argThat(e -> e.action().equals(AuditActions.EMAIL_SEND_QUEUED)
                && e.entityId().equals(original.toString()) && Boolean.TRUE.equals(e.details().get("replayed"))));
    }

    @Test
    void auditEntryNamesTheSendButNotItsRecipients() {
        when(store.claim(eq(CLUB), eq("k1"), anyString(), any())).thenReturn(Optional.empty());
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        UUID requestId = service.submit(request(Map.of()), "k1").response().requestId();

        verify(auditLog).record(argThat(e -> e.clubId().equals(CLUB) && e.productId().equals(PRODUCT)
                && e.entityId().equals(requestId.toString())
                && Integer.valueOf(1).equals(e.details().get("recipientCount"))
                && !e.details().toString().contains("a@x.com")));
    }

    @Test
    void sameKeyForADifferentRequestIsRefused() {
        when(store.claim(eq(CLUB), eq("k1"), anyString(), any())).thenReturn(Optional.of(
                new Entry("0".repeat(64), State.COMPLETED, UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> service.submit(request(Map.of()), "k1"))
                .isInstanceOf(Idempotency.IdempotencyKeyReusedException.class);
    }

    @Test
    void invalidRequestFreesTheKey() {
        when(store.claim(eq(CLUB), eq("k1"), anyString(), any())).thenReturn(Optional.empty());
        SendBulkEmailRequest noContent = new SendBulkEmailRequest(CLUB, PRODUCT, "club@x.com", null, null, null,
                null, null, null, List.of(EmailRecipient.of("a@x.com")));

        assertThatThrownBy(() -> service.submit(noContent, "k1")).isInstanceOf(IllegalArgumentException.class);
        verify(store).release(CLUB, "k1");
    }

    @Test
    void partlyQueuedSendKeepsTheKeyTaken() {
        when(store.claim(eq(CLUB), eq("k1"), anyString(), any())).thenReturn(Optional.empty());
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> service.submit(request(Map.of()), "k1")).isInstanceOf(SendNotQueuedException.class);
        verify(store).fail(CLUB, "k1", 1);
        verify(store, never()).release(any(), any());
    }

    private static SendBulkEmailRequest request(Map<String, String> recipientVariables) {
        return new SendBulkEmailRequest(CLUB, PRODUCT, "club@x.com", "Hi", "<p>Hi</p>", null, null, null, null,
                List.of(new EmailRecipient("a@x.com", recipientVariables)));
    }
}
