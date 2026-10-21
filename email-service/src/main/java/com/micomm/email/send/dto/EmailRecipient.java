package com.micomm.email.send.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * One recipient of a send and their own merge-field values (e.g. memberName, expiryDate), which
 * override the send-level ones. A plain JSON string ("a@x.com") is accepted as a recipient
 * without variables, so callers that send a list of addresses keep working.
 */
public record EmailRecipient(@NotBlank @Email String email, Map<String, String> variables) {

    public EmailRecipient {
        variables = variables != null ? Map.copyOf(variables) : Map.of();
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static EmailRecipient of(String email) {
        return new EmailRecipient(email, null);
    }
}
