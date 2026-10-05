package com.micomm.email.send.dto;

import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

public record SendBulkEmailRequest(
        @NotNull UUID clubId,
        @NotNull UUID productId,
        @NotBlank @Email String fromEmail,
        @NotBlank @Size(max = 500) String subject,
        @NotBlank String htmlBody,
        @NotEmpty @Size(max = 5000) List<@NotBlank @Email String> recipients
) {}