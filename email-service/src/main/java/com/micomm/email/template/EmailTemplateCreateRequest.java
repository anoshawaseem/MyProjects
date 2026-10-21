package com.micomm.email.template;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * moduleCode is required for clubs on module-aware schemas and must be omitted
 * for older clubs. templateKey optionally ties the template to a module
 * template type (one per club, product, module and key).
 */
public record EmailTemplateCreateRequest(
        @NotNull UUID clubId,
        @NotNull UUID productId,
        @Size(max = 50) String moduleCode,
        @Size(max = 100) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String templateKey,
        @NotBlank String name,
        @NotBlank String subject,
        @NotBlank String body
) {}
