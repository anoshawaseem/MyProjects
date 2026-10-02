package com.micomm.common.security;

import java.util.UUID;

/**
 * The authenticated principal available via SecurityContext after JWT validation.
 * clubId is null for ORG_ADMIN (platform-wide).
 */
public record MicommAuthenticatedUser(UUID userId, String role, UUID clubId) {

    public boolean isOrgAdmin() {
        return "ORG_ADMIN".equals(role);
    }

    public boolean isTenantAdmin() {
        return "TENANT_ADMIN".equals(role);
    }
}