package com.micomm.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ClubAccessGuardTest {

    private static final UUID CLUB_XYZ = UUID.randomUUID();
    private static final UUID CLUB_ABC = UUID.randomUUID();

    private final ClubAccessGuard guard = new ClubAccessGuard();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void orgAdminCanAccessAnyClub() {
        authenticateAs(new MicommAuthenticatedUser(UUID.randomUUID(), "ORG_ADMIN", null));

        assertThat(guard.requireClubAccess(CLUB_XYZ).isOrgAdmin()).isTrue();
        assertThat(guard.requireClubAccess(CLUB_ABC).isOrgAdmin()).isTrue();
    }

    @Test
    void tenantAdminCanAccessOwnClub() {
        MicommAuthenticatedUser user = new MicommAuthenticatedUser(UUID.randomUUID(), "TENANT_ADMIN", CLUB_XYZ);
        authenticateAs(user);

        assertThat(guard.requireClubAccess(CLUB_XYZ)).isEqualTo(user);
    }

    @Test
    void tenantAdminCannotAccessAnotherClub() {
        authenticateAs(new MicommAuthenticatedUser(UUID.randomUUID(), "TENANT_ADMIN", CLUB_XYZ));

        assertThatThrownBy(() -> guard.requireClubAccess(CLUB_ABC))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void tenantAdminWithoutClubClaimIsDenied() {
        authenticateAs(new MicommAuthenticatedUser(UUID.randomUUID(), "TENANT_ADMIN", null));

        assertThatThrownBy(() -> guard.requireClubAccess(CLUB_XYZ))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unknownRoleIsDeniedEvenForMatchingClub() {
        authenticateAs(new MicommAuthenticatedUser(UUID.randomUUID(), "SOMETHING_ELSE", CLUB_XYZ));

        assertThatThrownBy(() -> guard.requireClubAccess(CLUB_XYZ))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unauthenticatedIsDenied() {
        assertThatThrownBy(() -> guard.requireClubAccess(CLUB_XYZ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Not authenticated");
    }

    @Test
    void foreignPrincipalTypeIsDenied() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", null, List.of()));

        assertThatThrownBy(() -> guard.requireClubAccess(CLUB_XYZ))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingClubIdIsDenied() {
        authenticateAs(new MicommAuthenticatedUser(UUID.randomUUID(), "ORG_ADMIN", null));

        assertThatThrownBy(() -> guard.requireClubAccess(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static void authenticateAs(MicommAuthenticatedUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))));
    }
}
