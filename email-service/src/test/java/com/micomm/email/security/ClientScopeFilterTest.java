package com.micomm.email.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.security.ClientScopes;
import com.micomm.common.security.MicommAuthenticatedUser;

class ClientScopeFilterTest {

    private final ClientScopeFilter filter = new ClientScopeFilter(new ObjectMapper());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAsApp(String... scopes) {
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>(List.of(
                new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"),
                new SimpleGrantedAuthority(ClientScopes.CLIENT_TOKEN_AUTHORITY)));
        for (String s : scopes) {
            authorities.add(new SimpleGrantedAuthority(ClientScopes.authority(s)));
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new MicommAuthenticatedUser(UUID.randomUUID(), "TENANT_ADMIN", UUID.randomUUID()), null, authorities));
    }

    private static int call(ClientScopeFilter filter, String method, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest(method, path), response, chain);
        return chain.getRequest() != null ? 200 : response.getStatus();
    }

    @Test
    void appCanOnlyDoWhatItsScopesAllow() throws Exception {
        signInAsApp(ClientScopes.EMAIL_SEND);

        assertThat(call(filter, "POST", "/api/v1/emails/send-bulk")).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/v1/notifications/abc")).isEqualTo(403);
        assertThat(call(filter, "PUT", "/api/v1/emails/templates/abc")).isEqualTo(403);
    }

    @Test
    void endpointsWithoutAScopeAreClosedToApps() throws Exception {
        signInAsApp(ClientScopes.ALL.toArray(String[]::new));

        assertThat(call(filter, "GET", "/api/v1/emails/templates")).isEqualTo(200);
        assertThat(call(filter, "DELETE", "/api/v1/notifications/abc")).isEqualTo(403);
        assertThat(call(filter, "GET", "/api/v1/something-new")).isEqualTo(403);
    }

    @Test
    void multiChannelSendsNeedTheScopeOfEveryChannelSent() throws Exception {
        signInAsApp(ClientScopes.EMAIL_SEND, ClientScopes.SMS_SEND);

        // The filter only checks the app may send at all; the service checks the channels.
        assertThat(call(filter, "POST", "/api/v1/notifications/send")).isEqualTo(200);
        ClientScopeFilter.requireSendScopes(List.of("EMAIL", "SMS"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> ClientScopeFilter.requireSendScopes(List.of("EMAIL", "WHATSAPP")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("cannot send WHATSAPP").hasMessageContaining(ClientScopes.WHATSAPP_SEND);

        signInAsApp(ClientScopes.EMAIL_READ);
        assertThat(call(filter, "POST", "/api/v1/notifications/send")).isEqualTo(403);
    }

    @Test
    void usersAndApiKeysAreNotLimited() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new MicommAuthenticatedUser(UUID.randomUUID(), "TENANT_ADMIN", UUID.randomUUID()), null,
                List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));

        assertThat(call(filter, "DELETE", "/api/v1/emails/templates/abc")).isEqualTo(200);
        ClientScopeFilter.requireSendScopes(List.of("EMAIL", "SMS", "WHATSAPP"));
    }
}
