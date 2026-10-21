package com.micomm.email.send;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.ResolvedProviderDto;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.kafka.EmailResultMessage;
import com.micomm.email.kafka.EmailSendMessage;
import com.micomm.email.notification.NotificationWriter;
import com.micomm.email.provider.EmailProvider;
import com.micomm.email.provider.EmailProviderFactory;
import com.micomm.email.provider.EmailSendRequest;
import com.micomm.email.provider.EmailSendResult;
import com.micomm.email.template.TemplateRenderer;
import com.micomm.email.usage.EmailMessageWriter;
import com.micomm.email.usage.EmailMessageWriter.RecipientResult;
import com.micomm.email.usage.SendRecord;

@Component
public class EmailSendWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailSendWorker.class);

    private static final String CHANNEL = "EMAIL";

    private final TenantManagementClient tenantManagementClient;
    private final EmailMessageWriter writer;
    private final NotificationWriter notificationWriter;
    private final EmailProviderFactory providerFactory;
    private final KafkaTemplate<String, EmailResultMessage> resultKafkaTemplate;
    private final String emailResultTopic;
    private final int sendParallelism;
    // Provider calls block on network I/O; virtual threads keep that cheap. Concurrency is capped
    // per chunk by sendParallelism, not by the executor.
    private final ExecutorService sendExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public EmailSendWorker(
            TenantManagementClient tenantManagementClient,
            EmailMessageWriter writer,
            NotificationWriter notificationWriter,
            EmailProviderFactory providerFactory,
            KafkaTemplate<String, EmailResultMessage> resultKafkaTemplate,
            @Value("${kafka.topics.email-result}") String emailResultTopic,
            // Emails sent at once per chunk; a worker instance sends up to
            // (consumer concurrency x this) at once. Keep within the provider's rate limits.
            @Value("${micomm.email.send-parallelism:10}") int sendParallelism) {
        this.tenantManagementClient = tenantManagementClient;
        this.writer = writer;
        this.notificationWriter = notificationWriter;
        this.providerFactory = providerFactory;
        this.resultKafkaTemplate = resultKafkaTemplate;
        this.emailResultTopic = emailResultTopic;
        if (sendParallelism < 1) {
            throw new IllegalArgumentException("micomm.email.send-parallelism must be at least 1");
        }
        this.sendParallelism = sendParallelism;
    }

    /**
     * Sends one chunk of a send (older messages: the whole send). Chunks of the same send run on
     * different consumers at once; within a chunk up to {@code sendParallelism} emails are sent
     * concurrently. All chunks share one notifications / email_logs row, whose id is the requestId.
     */
    @KafkaListener(topics = "${kafka.topics.email-send}", containerFactory = "emailSendKafkaListenerContainerFactory")
    public void handle(EmailSendMessage message) {
        int chunkSize = message.recipients().size();
        log.info("Processing email send request [{}] chunk {}/{} for club [{}] / product [{}], {} recipients",
                message.requestId(), message.chunkIndex() + 1, Math.max(message.chunkCount(), 1),
                message.clubId(), message.productId(), chunkSize);

        SendRecord sendRecord = null;
        int successCount = 0;
        int failureCount = 0;
        List<EmailResultMessage.FailureDetail> failures = new ArrayList<>();
        List<Future<EmailSendResult>> sends = List.of();
        try {
            TenantDatabaseDto tenantDb = tenantManagementClient.resolveTenantDatabase(
                    message.clubId(), message.productId());
            // A module can have its own provider account; sends from no module use the product's.
            ResolvedProviderDto provider = tenantManagementClient.resolveProvider(
                    message.clubId(), message.productId(), "EMAIL", message.moduleCode());

            String schemaName = tenantDb.schemaName();
            EmailProvider emailProvider = providerFactory.get(provider.providerTypeCode());

            // Store the subject/body ONCE for this bulk/single send, scoped to the product, in the
            // tables of the tenant's layout (notifications for new clubs, email_logs for older ones).
            // For merged sends that is the template with its {{placeholders}}, not each recipient's copy.
            OffsetDateTime createdAt = message.submittedAt() != null ? message.submittedAt() : OffsetDateTime.now();
            sendRecord = tenantDb.usesNotificationsLayout()
                    ? notificationWriter.startNotification(tenantDb, message.requestId(), createdAt,
                            message.clubId(), message.productId(), message.moduleCode(), message.templateId(),
                            CHANNEL, message.fromEmail(), message.subject(), null, message.htmlBody(),
                            message.sendRecipientCount())
                    : writer.startEmailLog(schemaName, message.requestId(), createdAt, message.productId(),
                            message.subject(), message.htmlBody(), message.sendRecipientCount());

            sends = submitSends(message, emailProvider, provider);

            List<RecipientResult> pending = new ArrayList<>(EmailMessageWriter.RECIPIENT_BATCH_SIZE);
            for (int i = 0; i < chunkSize; i++) {
                String recipientEmail = message.recipients().get(i);
                EmailSendResult result = resultOf(sends.get(i));

                pending.add(new RecipientResult(recipientEmail, result.success(), result.errorMessage(),
                        Instant.now()));
                if (result.success()) {
                    successCount++;
                } else {
                    failureCount++;
                    failures.add(new EmailResultMessage.FailureDetail(recipientEmail, result.errorMessage()));
                }

                // Flush in batches so a crash mid-send still leaves what was already sent recorded.
                if (pending.size() >= EmailMessageWriter.RECIPIENT_BATCH_SIZE) {
                    sendRecord.recordRecipients(pending);
                    pending.clear();
                }
            }
            sendRecord.recordRecipients(pending);
            sendRecord.addResults(successCount, failureCount);

            Map<String, Long> usage = new LinkedHashMap<>();
            usage.put("EMAIL_MESSAGES_SENT", (long) successCount);
            usage.put("EMAIL_MESSAGES_FAILED", (long) failureCount);
            usage.put("EMAIL_RECIPIENTS_TOTAL", (long) chunkSize);
            writer.incrementUsageCounters(tenantDb, message.clubId(), message.productId(), usage);

            publishResult(message, successCount, failureCount, failures);

            log.info("Completed email send request [{}] chunk {}/{}: {} succeeded, {} failed",
                    message.requestId(), message.chunkIndex() + 1, Math.max(message.chunkCount(), 1),
                    successCount, failureCount);

        } catch (Exception ex) {
            log.error("Failed to process email send request [{}] chunk {}", message.requestId(),
                    message.chunkIndex() + 1, ex);
            sends.forEach(f -> f.cancel(true));
            // The failure may come from a stale cached schema/provider; re-resolve on the next message.
            tenantManagementClient.evict(message.clubId(), message.productId());
            // Count the rest of the chunk as failed, so the send's row still reaches a final status.
            int unprocessed = chunkSize - successCount - failureCount;
            if (sendRecord != null) {
                try {
                    sendRecord.addResults(successCount, failureCount + unprocessed);
                } catch (Exception recordEx) {
                    log.error("Could not record the failed chunk of request [{}]", message.requestId(), recordEx);
                }
            }
            failures.add(new EmailResultMessage.FailureDetail("*", "Processing error: " + ex.getMessage()));
            publishResult(message, successCount, failureCount + unprocessed, failures);
        }
    }

    /** Starts every send of the chunk; at most {@code sendParallelism} run at once. */
    private List<Future<EmailSendResult>> submitSends(EmailSendMessage message, EmailProvider emailProvider,
            ResolvedProviderDto provider) {
        Semaphore permits = new Semaphore(sendParallelism);
        List<Future<EmailSendResult>> sends = new ArrayList<>(message.recipients().size());
        for (int i = 0; i < message.recipients().size(); i++) {
            int index = i;
            sends.add(sendExecutor.submit(() -> {
                permits.acquire();
                try {
                    return sendOne(message, index, emailProvider, provider);
                } finally {
                    permits.release();
                }
            }));
        }
        return sends;
    }

    private static EmailSendResult sendOne(EmailSendMessage message, int index, EmailProvider emailProvider,
            ResolvedProviderDto provider) {
        String subject = message.subject();
        String htmlBody = message.htmlBody();
        try {
            if (message.merges()) {
                Map<String, String> variables = message.variablesFor(index);
                subject = TemplateRenderer.renderText(subject, variables);
                htmlBody = TemplateRenderer.renderHtml(htmlBody, variables);
            }
        } catch (IllegalArgumentException ex) {
            // Merge fields are validated before publishing; this only guards against a bad message.
            return EmailSendResult.failure("MERGE_FAILED", ex.getMessage());
        }
        return emailProvider.send(new EmailSendRequest(
                message.fromEmail(),
                message.recipients().get(index),
                subject,
                htmlBody,
                provider.secretReference(),
                provider.configJson()));
    }

    private static EmailSendResult resultOf(Future<EmailSendResult> send) throws InterruptedException {
        try {
            return send.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return EmailSendResult.failure("SEND_ERROR", cause.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        sendExecutor.shutdownNow();
    }

    private void publishResult(EmailSendMessage message, int successCount, int failureCount,
            List<EmailResultMessage.FailureDetail> failures) {
        EmailResultMessage result = new EmailResultMessage(
                message.requestId(),
                message.clubId(),
                message.productId(),
                message.recipients().size(),
                successCount,
                failureCount,
                failures,
                OffsetDateTime.now(),
                message.chunkIndex(),
                Math.max(message.chunkCount(), 1),
                message.sendRecipientCount());
        resultKafkaTemplate.send(emailResultTopic, message.requestId().toString(), result);
    }
}