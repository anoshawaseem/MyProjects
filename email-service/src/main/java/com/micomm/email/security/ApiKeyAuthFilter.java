package com.micomm.email.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.MicommActor;
import com.micomm.common.security.MicommAuthenticatedUser;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.VerifiedApiKeyDto;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates applications by the {@code X-Api-Key} header, as an alternative to a user's
 * bearer token. A valid key acts as TENANT_ADMIN of the key's club, so ClubAccessGuard limits it
 * to that club. A request with a missing, unknown, revoked or expired key in the header is
 * refused with 401 here, rather than falling through to a generic 403 later.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String ROLE = "TENANT_ADMIN";

    private final TenantManagementClient tenantManagementClient;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(TenantManagementClient tenantManagementClient, ObjectMapper objectMapper) {
        this.tenantManagementClient = tenantManagementClient;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<VerifiedApiKeyDto> verified;
        try {
            verified = key.isBlank() ? Optional.empty() : tenantManagementClient.verifyApiKey(sha256Hex(key.trim()));
        } catch (RuntimeException ex) {
            log.error("Could not verify API key", ex);
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "API key could not be checked; try again shortly");
            return;
        }
        if (verified.isEmpty()) {
            reject(response, HttpStatus.UNAUTHORIZED, "Invalid, revoked or expired API key");
            return;
        }

        VerifiedApiKeyDto apiKey = verified.get();
        // userId carries the API key's id; the details name the key in audit logs.
        MicommAuthenticatedUser principal = new MicommAuthenticatedUser(apiKey.apiKeyId(), ROLE, apiKey.clubId());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
        authentication.setDetails(MicommActor.apiKey(apiKey.apiKeyId(),
                apiKey.name() != null ? apiKey.name() : apiKey.apiKeyId().toString(),
                apiKey.keyPrefix() != null ? apiKey.keyPrefix() : "mck_"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
