package com.micomm.email.template;

import java.time.OffsetDateTime;
import java.util.UUID;

/** moduleCode / templateKey are set for club-scoped schemas only (null for older clubs). */
public record EmailTemplateDto(
        UUID id,
        String moduleCode,
        String templateKey,
        String name,
        String subject,
        String body,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
