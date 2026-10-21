package com.micomm.common.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.micomm.common.security.MicommActor;

/**
 * One thing that happened in a club, for its audit log.
 *
 * @param productId  the club's product it concerns, or null for club-wide events
 * @param action     what happened, e.g. EMAIL_SEND_QUEUED (see {@link AuditActions})
 * @param entityType / entityId what it happened to, e.g. TEMPLATE / the template's id
 * @param details    extra facts worth keeping; never secrets (passwords, keys, tokens)
 * @param actor      who did it; null means the current request's caller
 */
public record AuditEvent(UUID clubId, UUID productId, String action, String entityType, String entityId,
        Map<String, Object> details, MicommActor actor) {

    public static AuditEvent of(UUID clubId, UUID productId, String action, String entityType, Object entityId) {
        return new AuditEvent(clubId, productId, action, entityType, entityId != null ? entityId.toString() : null,
                new LinkedHashMap<>(), null);
    }

    /** Adds a detail; null values are left out. */
    public AuditEvent with(String key, Object value) {
        if (value != null) {
            details.put(key, value);
        }
        return this;
    }

    public AuditEvent by(MicommActor who) {
        return new AuditEvent(clubId, productId, action, entityType, entityId, details, who);
    }
}
