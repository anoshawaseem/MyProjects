package com.micomm.email.notification;

import java.util.Locale;

/**
 * Why a notification is sent. TRANSACTIONAL: messages a member needs about their own account or
 * activity (welcome, renewal reminder, booking confirmation). CAMPAIGN: marketing (newsletters,
 * promotions). Stored on layout "5" sends; older layouts have no column and count as
 * TRANSACTIONAL.
 */
public enum NotificationType {
    TRANSACTIONAL, CAMPAIGN;

    /** Null or blank means TRANSACTIONAL. */
    public static NotificationType parse(String value) {
        if (value == null || value.isBlank()) {
            return TRANSACTIONAL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("'type' must be TRANSACTIONAL or CAMPAIGN");
        }
    }
}
