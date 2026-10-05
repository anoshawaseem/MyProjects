package com.micomm.email.provider;

public record EmailSendRequest(
        String fromEmail,
        String toEmail,
        String subject,
        String htmlBody,
        String secretReference,  // plaintext API key / SMTP password
        String configJson        // provider-specific config (host, port, etc.)
) {}