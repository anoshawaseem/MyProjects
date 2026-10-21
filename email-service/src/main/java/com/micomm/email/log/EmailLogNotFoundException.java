package com.micomm.email.log;

import java.util.UUID;

public class EmailLogNotFoundException extends RuntimeException {
    public EmailLogNotFoundException(UUID id) {
        super("Email log not found: " + id);
    }
}
