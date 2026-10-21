package com.micomm.email.template;

import jakarta.validation.constraints.NotBlank;

public record EmailTemplateUpdateRequest(
        @NotBlank String name,
        @NotBlank String subject,
        @NotBlank String body
) {}