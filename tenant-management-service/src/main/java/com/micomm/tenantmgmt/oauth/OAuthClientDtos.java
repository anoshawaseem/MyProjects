package com.micomm.tenantmgmt.oauth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Request / response shapes for OAuth applications (clients). */
public final class OAuthClientDtos {

    private OAuthClientDtos() {
    }

    /**
     * publicKeyPem: the application's PUBLIC key as "-----BEGIN PUBLIC KEY-----" PEM (RSA 2048+
     * or EC P-256). The private key stays with the application. expiresAt is optional.
     */
    public record RegisterClientRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 10_000) String publicKeyPem,
            @NotEmpty List<@NotBlank String> scopes,
            @Future OffsetDateTime expiresAt) {
    }

    /** A registered application. The public key is not secret, so it is returned as well. */
    public record OAuthClientDto(UUID id, UUID clubId, String clientId, String name, String keyAlgorithm,
            List<String> scopes, String publicKeyPem, OffsetDateTime createdAt, OffsetDateTime expiresAt,
            OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {
    }

    /** What the token endpoint needs to check an assertion and issue a token. */
    record ActiveClient(UUID id, UUID clubId, String clientId, String name, String publicKeyPem,
            String keyAlgorithm, List<String> scopes) {
    }
}
