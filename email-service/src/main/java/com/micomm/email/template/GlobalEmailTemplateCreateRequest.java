package com.micomm.email.template;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** productId only locates the club's schema; the template is available in all of the club's products. */
public record GlobalEmailTemplateCreateRequest(
        @NotNull UUID clubId,
        @NotNull UUID productId,
        @NotBlank String name,
        @NotBlank String subject,
        @NotBlank String body
) {}
