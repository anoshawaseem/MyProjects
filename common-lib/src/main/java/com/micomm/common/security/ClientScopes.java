package com.micomm.common.security;

import java.util.List;

/**
 * Permissions an OAuth application (client) can be given. Users and API keys are not limited
 * by these; only access tokens issued to applications carry them.
 */
public final class ClientScopes {

    private ClientScopes() {
    }

    /** Send email (POST /api/v1/emails/send-bulk, and the EMAIL channel of /api/v1/notifications/send). */
    public static final String EMAIL_SEND = "email:send";
    /** The SMS channel of a multi-channel send (POST /api/v1/notifications/send). */
    public static final String SMS_SEND = "sms:send";
    /** The WHATSAPP channel of a multi-channel send. */
    public static final String WHATSAPP_SEND = "whatsapp:send";
    /** Read send history and per-recipient results. */
    public static final String EMAIL_READ = "email:read";
    /** List and read the club's templates. */
    public static final String TEMPLATES_READ = "templates:read";
    /** Create, edit and delete the club's templates. */
    public static final String TEMPLATES_WRITE = "templates:write";

    public static final List<String> ALL = List.of(EMAIL_SEND, SMS_SEND, WHATSAPP_SEND, EMAIL_READ, TEMPLATES_READ,
            TEMPLATES_WRITE);

    /** Scope needed to send on a channel (EMAIL, SMS, WHATSAPP), or null for channels without one. */
    public static String sendScope(String channel) {
        return switch (channel) {
            case "EMAIL" -> EMAIL_SEND;
            case "SMS" -> SMS_SEND;
            case "WHATSAPP" -> WHATSAPP_SEND;
            default -> null;
        };
    }

    /** Authority marking a request made with an application's access token. */
    public static final String CLIENT_TOKEN_AUTHORITY = "CLIENT_TOKEN";

    /** Authority granted for each scope a client token carries, e.g. SCOPE_email:send. */
    public static String authority(String scope) {
        return "SCOPE_" + scope;
    }
}
