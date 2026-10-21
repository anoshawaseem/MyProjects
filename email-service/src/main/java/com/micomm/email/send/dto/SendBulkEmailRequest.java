package com.micomm.email.send.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Either give subject + htmlBody directly, or send a stored template: by
 * templateId, or by moduleCode + templateKey (e.g. MEMBERSHIP / WELCOME).
 * moduleCode on a direct send records which module the email was sent from
 * (module-aware clubs only).
 *
 * Merge fields: {{placeholders}} are filled per recipient from, in increasing
 * priority, MiComm's own fields (clubName, productName), {@code variables}
 * (the same for every recipient) and each recipient's own {@code variables}.
 * Templates are always merged and every placeholder needs a value. A direct
 * send is merged only when the caller passes variables (send-level or on any
 * recipient); otherwise it is sent exactly as given.
 *
 * recipients: ["a@x.com", ...] or [{"email": "a@x.com", "variables": {...}}, ...].
 */
public record SendBulkEmailRequest(
        @NotNull UUID clubId,
        @NotNull UUID productId,
        @NotBlank @Email String fromEmail,
        @Size(max = 500) String subject,
        String htmlBody,
        @Size(max = 50) String moduleCode,
        UUID templateId,
        @Size(max = 100) String templateKey,
        Map<String, String> variables,
        @NotEmpty @Size(max = 5000) List<@NotNull @Valid EmailRecipient> recipients
) {

    public boolean usesTemplate() {
        return templateId != null || templateKey != null;
    }

    /** True when the caller passed any merge values, send-level or per recipient. */
    public boolean hasVariables() {
        return (variables != null && !variables.isEmpty())
                || recipients.stream().anyMatch(r -> !r.variables().isEmpty());
    }
}
