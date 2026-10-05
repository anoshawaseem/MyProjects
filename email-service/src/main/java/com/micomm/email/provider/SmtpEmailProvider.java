package com.micomm.email.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.UUID;

@Component
public class SmtpEmailProvider implements EmailProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String providerTypeCode() {
        return "SMTP";
    }

    @Override
    public EmailSendResult send(EmailSendRequest request) {
        try {
            JsonNode config = MAPPER.readTree(request.configJson());
            String host = config.path("host").asText();
            int port = config.path("port").asInt(587);
            String username = config.path("username").asText();

            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(host);
            sender.setPort(port);
            sender.setUsername(username);
            sender.setPassword(request.secretReference());

            Properties props = sender.getJavaMailProperties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");

            MimeMessage mimeMessage = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
            helper.setFrom(request.fromEmail());
            helper.setTo(request.toEmail());
            helper.setSubject(request.subject());
            helper.setText(request.htmlBody(), true);

            sender.send(mimeMessage);

            String providerMessageId = mimeMessage.getMessageID() != null
                    ? mimeMessage.getMessageID()
                    : "smtp-" + UUID.randomUUID();

            return EmailSendResult.success(providerMessageId, "250");

        } catch (Exception ex) {
            return EmailSendResult.failure("SMTP_SEND_FAILED", ex.getMessage());
        }
    }
}