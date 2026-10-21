package com.micomm.common.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Club-level authorization shared by all services. A clubId taken from a
 * request (path, query or body) is only a claim of what the caller wants;
 * call this before resolving tenant storage or doing anything with it.
 *
 * <ul>
 * <li>ORG_ADMIN (platform admin, no club_id claim): any club.</li>
 * <li>TENANT_ADMIN: only the club in their signed JWT club_id claim.</li>
 * <li>Anyone else, or no authenticated MicommAuthenticatedUser: denied.</li>
 * </ul>
 *
 * Only for request threads that carry a SecurityContext. Asynchronous
 * processing (e.g. Kafka consumers) has no user: authorize when the work is
 * accepted, not when it is processed.
 */
@Component
public class ClubAccessGuard {

    /**
     * Returns the caller if they may access {@code clubId}.
     *
     * @throws AccessDeniedException if not authenticated or not allowed
     */
    public MicommAuthenticatedUser requireClubAccess(UUID clubId) {
        if (clubId == null) {
            throw new AccessDeniedException("clubId is required");
        }
        MicommAuthenticatedUser user = currentUser();
        if (user.isOrgAdmin()) {
            return user;
        }
        if (user.isTenantAdmin() && clubId.equals(user.clubId())) {
            return user;
        }
        throw new AccessDeniedException("Access denied to club " + clubId);
    }

    /** @throws AccessDeniedException if the request is not authenticated with a MicommAuthenticatedUser */
    public MicommAuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof MicommAuthenticatedUser user)) {
            throw new AccessDeniedException("Not authenticated");
        }
        return user;
    }
}
