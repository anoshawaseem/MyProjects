package com.micomm.email.channel;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.stereotype.Component;

import com.micomm.email.channel.TextMessageProvider.TextMessageRequest;
import com.micomm.email.channel.TextMessageProvider.TextMessageResult;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.ResolvedProviderDto;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.kafka.ChannelSendMessage;
import com.micomm.email.notification.NotificationWriter;
import com.micomm.email.template.TemplateRenderer;
import com.micomm.email.usage.EmailMessageWriter;
import com.micomm.email.usage.EmailMessageWriter.RecipientResult;
import com.micomm.email.usage.SendRecord;

/**
 * Sends one SMS / WhatsApp chunk of a multi-channel send, the text-channel counterpart of
 * EmailSendWorker: resolves the channel's provider (the module's own, else the product's),
 * records the channel as a delivery of the send's notification, sends up to
 * {@code sendParallelism} messages at once and adds the chunk's results.
 */
@Component
public class ChannelSendWorker {

    private static final Logger log = LoggerFactory.getLogger(ChannelSendWorker.class);

    private final TenantManagementClient tenantManagementClient;
    private final EmailMessageWriter usageWriter;
    private final NotificationWriter notificationWriter;
    private final TextMessageProviders providers;
    private final int sendParallelism;
    private final ExecutorService sendExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChannelSendWorker(
            TenantManagementClient tenantManagementClient,
            EmailMessageWriter usageWriter,
            NotificationWriter notificationWriter,
            TextMessageProviders providers,
            @Value("${micomm.email.send-parallelism:10}") int sendParallelism) {
        this.tenantManagementClient = tenantManagementClient;
        this.usageWriter = usageWriter;
        this.notificationWriter = notificationWriter;
        this.providers = providers;
        if (sendParallelism < 1) {
            throw new IllegalArgumentException("micomm.email.send-parallelism must be at least 1");
        }
        this.sendParallelism = sendParallelism;
    }

    @KafkaListener(topics = "${kafka.topics.channel-send}", containerFactory = "channelSendKafkaListenerContainerFactory")
    public void handle(ChannelSendMessage message) {
        int chunkSize = message.recipients().size();
        log.info("Processing {} send [{}] chunk {}/{} for club [{}] / product [{}], {} recipients",
                message.channel(), message.requestId(), message.chunkIndex() + 1, message.chunkCount(),
                message.clubId(), message.productId(), chunkSize);

        TenantDatabaseDto tenant = null;
        SendRecord sendRecord = null;
        int successCount = 0;
        int failureCount = 0;
        List<Future<TextMessageResult>> sends = List.of();
        try {
            tenant = tenantManagementClient.resolveTenantDatabase(message.clubId(),
                    message.productId());
            if (!tenant.usesDeliveries()) {
                // Checked when the send was accepted; only a schema change since could get here.
                throw new IllegalStateException("Schema " + tenant.schemaName() + " has no notification_deliveries");
            }
            ResolvedProviderDto account = tenantManagementClient.resolveProvider(message.clubId(),
                    message.productId(), message.channel(), message.moduleCode());
            TextMessageProvider provider = providers.get(account.providerTypeCode(), message.channel());

            sendRecord = startDelivery(tenant, message, provider.sender(account.configJson()),
                    account.providerAccountId());

            sends = submitSends(message, provider, account);

            List<RecipientResult> pending = new ArrayList<>(EmailMessageWriter.RECIPIENT_BATCH_SIZE);
            for (int i = 0; i < chunkSize; i++) {
                TextMessageResult result = resultOf(sends.get(i));
                pending.add(new RecipientResult(message.recipients().get(i), result.success(),
                        result.success() ? null : result.errorCode() + ": " + result.errorMessage(), Instant.now()));
                if (result.success()) {
                    successCount++;
                } else {
                    failureCount++;
                }
                if (pending.size() >= EmailMessageWriter.RECIPIENT_BATCH_SIZE) {
                    sendRecord.recordRecipients(pending);
                    pending.clear();
                }
            }
            sendRecord.recordRecipients(pending);
            sendRecord.addResults(successCount, failureCount);

            Map<String, Long> usage = new LinkedHashMap<>();
            usage.put(message.channel() + "_MESSAGES_SENT", (long) successCount);
            usage.put(message.channel() + "_MESSAGES_FAILED", (long) failureCount);
            usage.put(message.channel() + "_RECIPIENTS_TOTAL", (long) chunkSize);
            usageWriter.incrementUsageCounters(tenant, message.clubId(), message.productId(), usage);

            log.info("Completed {} send [{}] chunk {}/{}: {} succeeded, {} failed", message.channel(),
                    message.requestId(), message.chunkIndex() + 1, message.chunkCount(), successCount, failureCount);
        } catch (Exception ex) {
            log.error("Failed to process {} send [{}] chunk {}", message.channel(), message.requestId(),
                    message.chunkIndex() + 1, ex);
            sends.forEach(f -> f.cancel(true));
            tenantManagementClient.evict(message.clubId(), message.productId());
            // Count the rest of the chunk as failed, so the delivery still reaches a final status. If
            // it failed before the delivery was written (e.g. no provider any more), write it now:
            // the notification waits for every channel's delivery before it gets a final status.
            if (sendRecord != null || (tenant != null && tenant.usesDeliveries())) {
                try {
                    if (sendRecord == null) {
                        sendRecord = startDelivery(tenant, message, null, null);
                    }
                    sendRecord.addResults(successCount, chunkSize - successCount);
                } catch (Exception recordEx) {
                    log.error("Could not record the failed chunk of request [{}]", message.requestId(), recordEx);
                }
            }
        }
    }

    private SendRecord startDelivery(TenantDatabaseDto tenant, ChannelSendMessage message, String sender,
            UUID providerAccountId) {
        OffsetDateTime createdAt = message.submittedAt() != null ? message.submittedAt() : OffsetDateTime.now();
        return notificationWriter.startNotification(tenant, message.requestId(), createdAt, message.clubId(),
                message.productId(), message.moduleCode(), null, message.channel(), sender, null, message.text(),
                null, message.totalRecipients(), message.type(), providerAccountId,
                message.notificationRecipientCount(), message.channelCount());
    }

    private List<Future<TextMessageResult>> submitSends(ChannelSendMessage message, TextMessageProvider provider,
            ResolvedProviderDto account) {
        Semaphore permits = new Semaphore(sendParallelism);
        List<Future<TextMessageResult>> sends = new ArrayList<>(message.recipients().size());
        for (int i = 0; i < message.recipients().size(); i++) {
            int index = i;
            sends.add(sendExecutor.submit(() -> {
                permits.acquire();
                try {
                    return sendOne(message, index, provider, account);
                } finally {
                    permits.release();
                }
            }));
        }
        return sends;
    }

    private static TextMessageResult sendOne(ChannelSendMessage message, int index, TextMessageProvider provider,
            ResolvedProviderDto account) {
        String text;
        try {
            text = message.variables() != null
                    ? TemplateRenderer.renderText(message.text(), message.variablesFor(index))
                    : message.text();
        } catch (IllegalArgumentException ex) {
            // Merge fields are validated before publishing; this only guards against a bad message.
            return TextMessageResult.failure("MERGE_FAILED", ex.getMessage());
        }
        return provider.send(new TextMessageRequest(message.recipients().get(index), text,
                account.secretReference(), account.configJson()));
    }

    private static TextMessageResult resultOf(Future<TextMessageResult> send) throws InterruptedException {
        try {
            return send.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return TextMessageResult.failure("SEND_ERROR", cause.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        sendExecutor.shutdownNow();
    }
}
