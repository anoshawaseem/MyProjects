package com.micomm.email.send;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

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
import com.micomm.email.provider.EmailProvider;
import com.micomm.email.provider.EmailProviderFactory;
import com.micomm.email.provider.EmailSendRequest;
import com.micomm.email.provider.EmailSendResult;
import com.micomm.email.usage.EmailMessageWriter;

@Component
public class EmailSendWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailSendWorker.class);

    private final TenantManagementClient tenantManagementClient;
    private final EmailMessageWriter writer;
    private final EmailProviderFactory providerFactory;
    private final KafkaTemplate<String, EmailResultMessage> resultKafkaTemplate;
    private final String emailResultTopic;

    public EmailSendWorker(
            TenantManagementClient tenantManagementClient,
            EmailMessageWriter writer,
            EmailProviderFactory providerFactory,
            KafkaTemplate<String, EmailResultMessage> resultKafkaTemplate,
            @Value("${kafka.topics.email-result}") String emailResultTopic) {
        this.tenantManagementClient = tenantManagementClient;
        this.writer = writer;
        this.providerFactory = providerFactory;
        this.resultKafkaTemplate = resultKafkaTemplate;
        this.emailResultTopic = emailResultTopic;
    }

    @KafkaListener(
            topics = "${kafka.topics.email-send}",
            containerFactory = "emailSendKafkaListenerContainerFactory")
    public void handle(EmailSendMessage message) {
        log.info("Processing email send request [{}] for club [{}] / product [{}],{} recipients",
                message.requestId(), message.clubId(), message.productId(), message.recipients().size());

        try {
            TenantDatabaseDto tenantDb = tenantManagementClient.resolveTenantDatabase(
                    message.clubId(), message.productId());
            ResolvedProviderDto provider = tenantManagementClient.resolveProvider(
                    message.clubId(), message.productId(), "EMAIL");

            String schemaName = tenantDb.schemaName();
            EmailProvider emailProvider = providerFactory.get(provider.providerTypeCode());

            int successCount = 0;
            int failureCount = 0;
            List<EmailResultMessage.FailureDetail> failures = new ArrayList<>();

            for (String recipientEmail : message.recipients()) {
                EmailSendResult result = emailProvider.send(new EmailSendRequest(
                        message.fromEmail(),
                        recipientEmail,
                        message.subject(),
                        message.htmlBody(),
                        provider.secretReference(),
                        provider.configJson()
                ));

                String status = result.success() ? "SENT" : "FAILED";
                writer.insertEmailLog(schemaName, recipientEmail, message.subject(), status);

                if (result.success()) {
                    successCount++;
                } else {
                    failureCount++;
                    failures.add(new EmailResultMessage.FailureDetail(recipientEmail, result.errorMessage()));
                }
            }

            writer.incrementUsageCounter(schemaName, "EMAIL_MESSAGES_SENT", successCount);
            writer.incrementUsageCounter(schemaName, "EMAIL_MESSAGES_FAILED", failureCount);
            writer.incrementUsageCounter(schemaName, "EMAIL_RECIPIENTS_TOTAL", message.recipients().size());

            publishResult(message, successCount, failureCount, failures);

            log.info("Completed email send request [{}]: {} succeeded, {} failed",
                    message.requestId(), successCount, failureCount);

        } catch (Exception ex) {
            log.error("Failed to process email send request [{}]", message.requestId(), ex);
            publishResult(message, 0, message.recipients().size(),
                    List.of(new EmailResultMessage.FailureDetail("*", "Processing error: " + ex.getMessage())));
        }
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
                OffsetDateTime.now()
        );
        String partitionKey = message.clubId() + ":" + message.productId();
        resultKafkaTemplate.send(emailResultTopic, partitionKey, result);
    }
}