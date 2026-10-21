package com.micomm.email.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.security.MicommActor;
import com.micomm.common.security.MicommAuthenticatedUser;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.VerifiedApiKeyDto;

class ApiKeyAuthFilterTest {

    private final TenantManagementClient client = mock(TenantManagementClient.class);
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(client, new ObjectMapper());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestsWithoutTheHeaderPassThroughUntouched() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(client);
    }

    @Test
    void validKeyActsAsTenantAdminOfItsClubAndOnlySendsTheHash() throws Exception {
        UUID keyId = UUID.randomUUID();
        UUID clubId = UUID.randomUUID();
        String key = "mck_abcdefghjk_secret";
        when(client.verifyApiKey(ApiKeyAuthFilter.sha256Hex(key)))
                .thenReturn(Optional.of(new VerifiedApiKeyDto(keyId, clubId, "MiClub", "mck_abcdefghjk")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.HEADER, key);
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                // Checked inside the chain: that is where the controllers see the caller.
                Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                assertThat(principal).isEqualTo(new MicommAuthenticatedUser(keyId, "TENANT_ADMIN", clubId));
                // Audit logs name the key, never show its secret.
                assertThat(MicommActor.current())
                        .isEqualTo(new MicommActor(MicommActor.Type.API_KEY, keyId, "API key 'MiClub' (mck_abcdefghjk)"));
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    void unknownKeyIsRefusedWith401() throws Exception {
        when(client.verifyApiKey(anyString())).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.HEADER, "mck_wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"success\":false").contains("Invalid, revoked or expired");
        assertThat(chain.getRequest()).isNull();
    }
}
