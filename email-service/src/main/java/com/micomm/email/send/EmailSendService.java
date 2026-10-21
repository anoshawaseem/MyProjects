package com.micomm.email.send;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.email.audit.EmailAuditLog;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.kafka.EmailSendMessage;
import com.micomm.email.send.dto.EmailRecipient;
import com.micomm.email.send.dto.SendBulkEmailRequest;
import com.micomm.email.send.dto.SendBulkEmailResponse;
import com.micomm.email.template.EmailTemplateDto;
import com.micomm.email.template.EmailTemplateService;
import com.micomm.email.template.TemplateRenderer;
import com.micomm.email.tenant.TenantModules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class EmailSendService {

    /** Recipients named in a missing-merge-fields error; the rest are only counted. */
    private static final int MAX_REPORTED_RECIPIENTS = 5;

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(15);

    private final KafkaTemplate<String, EmailSendMessage> kafkaTemplate;
    private final String emailSendTopic;
    private final int chunkSize;
    private final int maxMessageBytes;
    private final ObjectMapper objectMapper;
    private final ClubAccessGuard clubAccessGuard;
    private final TenantManagementClient tenantManagementClient;
    private final EmailTemplateService templateService;
    private final IdempotencyStore idempotencyStore;
    private final EmailAuditLog auditLog;

    public EmailSendService(
            KafkaTemplate<String, EmailSendMessage> kafkaTemplate,
            @Value("${kafka.topics.email-send}") String emailSendTopic,
            // Recipients per Kafka message: the unit one worker sends.
            @Value("${micomm.email.send-chunk-size:500}") int chunkSize,
            // Below Kafka's default 1 MB producer / broker limit, leaving room for headers.
            @Value("${micomm.email.max-send-message-bytes:900000}") int maxMessageBytes,
            ObjectMapper objectMapper,
            ClubAccessGuard clubAccessGuard,
            TenantManagementClient tenantManagementClient,
            EmailTemplateService templateService,
            IdempotencyStore idempotencyStore,
            EmailAuditLog auditLog) {
        this.kafkaTemplate = kafkaTemplate;
        this.emailSendTopic = emailSendTopic;
        if (chunkSize < 1) {
            throw new IllegalArgumentException("micomm.email.send-chunk-size must be at least 1");
        }
        this.chunkSize = chunkSize;
        this.maxMessageBytes = maxMessageBytes;
        this.objectMapper = objectMapper;
        this.clubAccessGuard = clubAccessGuard;
        this.tenantManagementClient = tenantManagementClient;
        this.templateService = templateService;
        this.idempotencyStore = idempotencyStore;
        this.auditLog = auditLog;
    }

    /** What is sent: subject/body (with placeholders when merged) plus where it came from. */
    private record Content(TenantDatabaseDto tenant, String subject, String htmlBody, String moduleCode,
            UUID templateId) {
    }

    /** The send's response; replayed is true when it is the answer to an earlier, identical request. */
    public record Submission(SendBulkEmailResponse response, boolean replayed) {
    }

    /**
     * Queues a send. With an idempotency key, a repeat of the same request (same club, key and
     * body) within the key's lifetime returns the original send instead of sending again.
     */
    public Submission submit(SendBulkEmailRequest request, String idempotencyKey) {
        // The Kafka worker has no user context and acts on whatever club the message names,
        // so the caller must be authorized for the club before the message is published.
        clubAccessGuard.requireClubAccess(request.clubId());

        UUID requestId = UUID.randomUUID();
        String key = Idempotency.normalizeKey(idempotencyKey);
        if (key == null) {
            return audited(request, null, new Submission(queue(request, requestId), false));
        }

        String requestHash = Idempotency.requestHash(objectMapper, request);
        Optional<IdempotencyStore.Entry> earlier = idempotencyStore.claim(request.clubId(), key, requestHash,
                requestId);
        if (earlier.isPresent()) {
            return audited(request, key, new Submission(Idempotency.replay(earlier.get(), requestHash), true));
        }
        try {
            SendBulkEmailResponse response = queue(request, requestId);
            idempotencyStore.complete(request.clubId(), key, response.recipientCount());
            return audited(request, key, new Submission(response, false));
        } catch (SendNotQueuedException ex) {
            idempotencyStore.fail(request.clubId(), key, request.recipients().size());
            throw ex;
        } catch (RuntimeException ex) {
            // Nothing was queued (e.g. an invalid request), so the same key may be used again.
            idempotencyStore.release(request.clubId(), key);
            throw ex;
        }
    }

    /** Records the accepted send (or its replay) in the club's audit log; no recipients or content. */
    private Submission audited(SendBulkEmailRequest request, String idempotencyKey, Submission submission) {
        auditLog.record(AuditEvent.of(request.clubId(), request.productId(), AuditActions.EMAIL_SEND_QUEUED,
                        "EMAIL_SEND", submission.response().requestId())
                .with("recipientCount", submission.response().recipientCount())
                .with("module", TenantModules.normalize(request.moduleCode()))
                .with("templateId", request.templateId())
                .with("templateKey", request.templateKey())
                .with("fromEmail", request.fromEmail())
                .with("idempotencyKey", idempotencyKey)
                .with("replayed", submission.replayed() ? Boolean.TRUE : null));
        return submission;
    }

    private SendBulkEmailResponse queue(SendBulkEmailRequest request, UUID requestId) {
        Content content = resolveContent(request);

        // Templates are always merged; a direct send only when the caller passed values for it.
        Map<String, String> variables = null;
        List<Map<String, String>> recipientVariables = null;
        if (request.usesTemplate() || request.hasVariables()) {
            variables = sendVariables(content.tenant(), request);
            requireAllMergeFields(content, variables, request.recipients());
            if (request.recipients().stream().anyMatch(r -> !r.variables().isEmpty())) {
                recipientVariables = request.recipients().stream().map(EmailRecipient::variables).toList();
            }
        }

        OffsetDateTime submittedAt = OffsetDateTime.now();
        List<String> emails = request.recipients().stream().map(EmailRecipient::email).toList();
        int total = emails.size();
        int chunkCount = (total + chunkSize - 1) / chunkSize;

        List<EmailSendMessage> chunks = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            int from = i * chunkSize;
            int to = Math.min(from + chunkSize, total);
            EmailSendMessage chunk = new EmailSendMessage(
                    requestId,
                    "email-" + requestId + "-" + i,
                    request.clubId(),
                    request.productId(),
                    request.fromEmail(),
                    content.subject(),
                    content.htmlBody(),
                    emails.subList(from, to),
                    submittedAt,
                    content.moduleCode(),
                    content.templateId(),
                    variables,
                    recipientVariables != null ? recipientVariables.subList(from, to) : null,
                    i,
                    chunkCount,
                    total);
            requireFitsInOneMessage(chunk);
            chunks.add(chunk);
        }

        publish(chunks);
        return new SendBulkEmailResponse(requestId, "QUEUED", total);
    }

    /**
     * Keys each chunk by request + chunk so a send's chunks spread over the topic's partitions and
     * are picked up by different workers at once. Waits for Kafka to acknowledge every chunk, so
     * QUEUED is only returned once the whole send is safely queued.
     */
    private void publish(List<EmailSendMessage> chunks) {
        CompletableFuture<?>[] sends = chunks.stream()
                .map(c -> kafkaTemplate.send(emailSendTopic, c.requestId() + ":" + c.chunkIndex(), c))
                .toArray(CompletableFuture[]::new);
        UUID requestId = chunks.get(0).requestId();
        try {
            CompletableFuture.allOf(sends).get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SendNotQueuedException(requestId, ex);
        } catch (ExecutionException | TimeoutException ex) {
            // Chunks already acknowledged are still sent; the caller sees the send was not fully queued.
            throw new SendNotQueuedException(requestId, ex);
        }
    }

    /**
     * Direct sends use the request's subject/body; template sends the stored template's. Either
     * may still contain placeholders: they are filled per recipient by the worker, after
     * {@link #requireAllMergeFields} has checked here, with the caller's authorization, that every
     * recipient has a value for each, so a bad request is a 4xx rather than a failed send later.
     */
    private Content resolveContent(SendBulkEmailRequest request) {
        String moduleCode = TenantModules.normalize(request.moduleCode());

        if (!request.usesTemplate()) {
            if (isBlank(request.subject()) || isBlank(request.htmlBody())) {
                throw new IllegalArgumentException(
                        "Give 'subject' and 'htmlBody', or a template ('templateId' or 'moduleCode' + 'templateKey')");
            }
            TenantDatabaseDto tenant = resolveTenant(request, moduleCode);
            if (moduleCode != null) {
                TenantModules.requireEnabled(tenant, moduleCode);
            }
            return new Content(tenant, request.subject(), request.htmlBody(), moduleCode, null);
        }

        if (request.templateId() == null && moduleCode == null) {
            throw new IllegalArgumentException("'templateKey' needs 'moduleCode'");
        }
        TenantDatabaseDto tenant = resolveTenant(request, moduleCode);
        EmailTemplateDto template = templateService.findForSend(tenant, request.clubId(), request.productId(),
                moduleCode, request.templateId(), request.templateKey());
        return new Content(
                tenant,
                template.subject(),
                template.body(),
                // Global templates have no module; the send keeps the module it was made from, if any.
                template.moduleCode() != null ? template.moduleCode() : moduleCode,
                template.id());
    }

    /** MiComm's own merge fields, overridden by the caller's send-level values. */
    private static Map<String, String> sendVariables(TenantDatabaseDto tenant, SendBulkEmailRequest request) {
        Map<String, String> variables = new HashMap<>();
        if (tenant.clubName() != null) {
            variables.put("clubName", tenant.clubName());
        }
        if (tenant.productName() != null) {
            variables.put("productName", tenant.productName());
        }
        if (request.variables() != null) {
            variables.putAll(request.variables());
        }
        return variables;
    }

    private static void requireAllMergeFields(Content content, Map<String, String> sendVariables,
            List<EmailRecipient> recipients) {
        Set<String> needed = new LinkedHashSet<>(TemplateRenderer.placeholders(content.subject()));
        needed.addAll(TemplateRenderer.placeholders(content.htmlBody()));
        needed.removeIf(name -> sendVariables.get(name) != null);
        if (needed.isEmpty()) {
            return;
        }

        List<String> problems = new ArrayList<>();
        int failing = 0;
        for (EmailRecipient recipient : recipients) {
            List<String> missing = needed.stream().filter(name -> recipient.variables().get(name) == null).toList();
            if (!missing.isEmpty() && failing++ < MAX_REPORTED_RECIPIENTS) {
                problems.add(recipient.email() + " (" + String.join(", ", missing) + ")");
            }
        }
        if (failing > 0) {
            throw new IllegalArgumentException("Missing merge fields for " + failing + " recipient(s): "
                    + String.join("; ", problems) + (failing > MAX_REPORTED_RECIPIENTS ? "; ..." : ""));
        }
    }

    /** Kafka rejects an oversized message; possible only with very large per-recipient values. */
    private void requireFitsInOneMessage(EmailSendMessage message) {
        int size;
        try {
            size = objectMapper.writeValueAsBytes(message).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise email send message", ex);
        }
        if (size > maxMessageBytes) {
            throw new IllegalArgumentException("Recipient merge values are too large (" + size + " bytes for "
                    + message.recipients().size() + " recipients, limit " + maxMessageBytes
                    + "); lower micomm.email.send-chunk-size or send less data per recipient");
        }
    }

    private TenantDatabaseDto resolveTenant(SendBulkEmailRequest request, String moduleCode) {
        return tenantManagementClient.resolveTenantDatabase(request.clubId(), request.productId(), moduleCode);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
