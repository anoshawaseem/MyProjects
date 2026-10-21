package com.micomm.email.template;

import java.util.UUID;

public class EmailTemplateNotFoundException extends RuntimeException {
    public EmailTemplateNotFoundException(UUID id) {
        super("Email template not found: " + id);
    }

    /** For lookups by module template key, e.g. "MEMBERSHIP/WELCOME". */
    public EmailTemplateNotFoundException(String reference) {
        super("Email template not found: " + reference);
    }
}
