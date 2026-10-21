package com.micomm.common.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is making the current request, for audit logs: a signed-in user, an application using a
 * club API key, or MiComm itself (background work with no caller). Stored as the
 * Authentication's details by JwtAuthFilter and the API key filter.
 *
 * @param label readable name kept with each audit entry (an email, or "API key 'MiClub' (mck_...)"),
 *              so the log stays readable even after the user or key is gone
 */
public record MicommActor(Type type, UUID id, String label) {

    public enum Type { USER, API_KEY, SYSTEM }

    public static final MicommActor SYSTEM = new MicommActor(Type.SYSTEM, null, "MiComm");

    public static MicommActor user(UUID userId, String email) {
        return new MicommActor(Type.USER, userId, email != null && !email.isBlank() ? email : "user " + userId);
    }

    public static MicommActor apiKey(UUID apiKeyId, String name, String keyPrefix) {
        return new MicommActor(Type.API_KEY, apiKeyId, "API key '" + name + "' (" + keyPrefix + ")");
    }

    /** The current request's actor, or SYSTEM when there is no authenticated caller. */
    public static MicommActor current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return SYSTEM;
        }
        if (authentication.getDetails() instanceof MicommActor actor) {
            return actor;
        }
        if (authentication.getPrincipal() instanceof MicommAuthenticatedUser user) {
            return user(user.userId(), null);
        }
        return SYSTEM;
    }
}
