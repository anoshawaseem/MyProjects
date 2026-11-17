package com.micomm.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthFilterTest {

    private final JwtTokenProvider provider = provider();
    private final UUID appId = UUID.randomUUID();
    private final UUID clubId = UUID.randomUUID();

    private static JwtTokenProvider provider() {
        JwtProperties props = new JwtProperties();
        props.setSecret("dGhpc2lzYXRlc3RzZWNyZXRrZXlmb3Jqd3RzaWduaW5nMzJieXRlcw==");
        return new JwtTokenProvider(props);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** Runs the filter and returns who the request was authenticated as, inside the chain. */
    private Authentication authenticate(JwtAuthFilter filter, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        Authentication[] seen = new Authentication[1];
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen[0] = SecurityContextHolder.getContext().getAuthentication();
            }
        });
        return seen[0];
    }

    private String appToken() {
        return provider.generateClientToken(appId, "mcc_x", "MiClub", clubId, List.of("email:send"),
                Duration.ofMinutes(15));
    }

    @Test
    void serviceThatDoesNotAcceptAppTokensLeavesThemUnauthenticated() throws Exception {
        assertThat(authenticate(new JwtAuthFilter(provider), appToken())).isNull();
    }

    @Test
    void serviceThatAcceptsAppTokensMarksThemWithTheirScopes() throws Exception {
        Authentication auth = authenticate(new JwtAuthFilter(provider, true), appToken());

        assertThat(auth.getPrincipal()).isEqualTo(new MicommAuthenticatedUser(appId, "TENANT_ADMIN", clubId));
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains(ClientScopes.CLIENT_TOKEN_AUTHORITY, "SCOPE_email:send");
        assertThat(auth.getDetails()).isEqualTo(MicommActor.apiClient(appId, "MiClub", "mcc_x"));
    }

    @Test
    void userTokensWorkInBothKindsOfService() throws Exception {
        String token = provider.generateAccessToken(UUID.randomUUID(), "TENANT_ADMIN", clubId, "a@club.test");

        assertThat(authenticate(new JwtAuthFilter(provider), token)).isNotNull();
        assertThat(authenticate(new JwtAuthFilter(provider, true), token).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).doesNotContain(ClientScopes.CLIENT_TOKEN_AUTHORITY);
    }
}
