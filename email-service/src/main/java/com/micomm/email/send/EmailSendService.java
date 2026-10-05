package com.micomm.email.send;

import com.micomm.email.kafka.EmailSendMessage;
import com.micomm.email.send.dto.SendBulkEmailRequest;
import com.micomm.email.send.dto.SendBulkEmailResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class EmailSendService {

    private final KafkaTemplate<String, EmailSendMessage> kafkaTemplate;
    private final String emailSendTopic;

    public EmailSendService(
            KafkaTemplate<String, EmailSendMessage> kafkaTemplate,
            @Value("${kafka.topics.email-send}") String emailSendTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.emailSendTopic = emailSendTopic;
    }

    public SendBulkEmailResponse submit(SendBulkEmailRequest request) {
        UUID requestId = UUID.randomUUID();
        String idempotencyKey = "email-" + requestId;

        EmailSendMessage message = new EmailSendMessage(
                requestId,
                idempotencyKey,
                request.clubId(),
                request.productId(),
                request.fromEmail(),
                request.subject(),
                request.htmlBody(),
                request.recipients(),
                OffsetDateTime.now()
        );

        // Key by clubId+productId so all messages for the same tenant
        // land on the same partition, preserving per-tenant ordering.
        String partitionKey = request.clubId() + ":" + request.productId();
        kafkaTemplate.send(emailSendTopic, partitionKey, message);

        return new SendBulkEmailResponse(requestId, "QUEUED", request.recipients().size());
    }
}