package com.micomm.email.channel.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One message sent to the same people on several channels at once (POST /api/v1/notifications/send).
 *
 * Content: {@code title} and {@code body} (plain text) are the message; each channel can
 * override them. EMAIL: subject = email.subject, else title; HTML = email.html, else body
 * (escaped, line breaks kept). SMS / WHATSAPP: text = sms.text / whatsapp.text, else title and
 * body on separate lines.
 *
 * Recipients: each person gives the addresses they have: email, phone (SMS, and WhatsApp unless
 * whatsapp is given) and whatsapp. Each channel goes to the recipients with an address for it;
 * every recipient needs an address on at least one requested channel.
 *
 * Merge fields: {{placeholders}} in any channel's content are filled per recipient from, in
 * increasing priority, MiComm's own fields (clubName, productName), {@code variables} and the
 * recipient's own variables. Every placeholder needs a value for every recipient of that channel.
 */
public record SendNotificationRequest(
        @NotNull UUID clubId,
        @NotNull UUID productId,
        @NotEmpty @Size(max = 4) List<@NotNull @Pattern(regexp = "^(?i)(EMAIL|SMS|WHATSAPP|PUSH)$",
                message = "must be EMAIL, SMS or WHATSAPP") String> channels,
        @Size(max = 50) String moduleCode,
        @Pattern(regexp = "^(?i)(TRANSACTIONAL|CAMPAIGN)$", message = "must be TRANSACTIONAL or CAMPAIGN") String type,
        @Size(max = 500) String title,
        @Size(max = 100_000) String body,
        @Valid EmailContent email,
        @Valid TextContent sms,
        @Valid TextContent whatsapp,
        Map<String, String> variables,
        @NotEmpty @Size(max = 5000) List<@NotNull @Valid NotificationRecipient> recipients
) {

    /** fromEmail: defaults to the email provider account's fromEmail. */
    public record EmailContent(@Email String fromEmail, @Size(max = 500) String subject, String html) {
    }

    /** An SMS of up to 10 segments; Twilio's limit is 1600 characters. */
    public record TextContent(@Size(max = 1600) String text) {
    }

    /** Phone numbers in E.164 format, e.g. +447700900123. */
    public record NotificationRecipient(
            @Email String email,
            @Pattern(regexp = E164, message = "must be an E.164 number, e.g. +447700900123") String phone,
            @Pattern(regexp = E164, message = "must be an E.164 number, e.g. +447700900123") String whatsapp,
            Map<String, String> variables) {

        public NotificationRecipient {
            variables = variables != null ? Map.copyOf(variables) : Map.of();
        }

        /** This recipient's address on a channel, or null if they have none. */
        public String address(String channel) {
            return switch (channel) {
                case "EMAIL" -> blankToNull(email);
                case "SMS" -> blankToNull(phone);
                case "WHATSAPP" -> blankToNull(whatsapp) != null ? whatsapp : blankToNull(phone);
                default -> null;
            };
        }

        /** How the recipient is named in errors. */
        public String describe() {
            return blankToNull(email) != null ? email : blankToNull(phone) != null ? phone : whatsapp;
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }

    public static final String E164 = "^\\+[1-9][0-9]{6,14}$";
}
