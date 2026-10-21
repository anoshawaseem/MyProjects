package com.micomm.tenantmgmt.apikey;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request / response shapes for club API keys. */
public final class ApiKeyDtos {

    private ApiKeyDtos() {
    }

    /** expiresAt is optional; without it the key works until it is revoked. */
    public record CreateApiKeyRequest(
            @NotBlank @Size(max = 100) String name,
            @Future OffsetDateTime expiresAt) {
    }

    /** A key as listed: never the key itself, only its public prefix. */
    public record ApiKeyDto(UUID id, UUID clubId, String name, String keyPrefix, OffsetDateTime createdAt,
            OffsetDateTime expiresAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {
    }

    /** Returned once, on creation: {@code key} cannot be retrieved again. */
    public record CreatedApiKeyDto(ApiKeyDto apiKey, String key) {
    }

    /** email-service sends the key's SHA-256 hash (hex), never the key. */
    public record VerifyApiKeyRequest(@NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String keyHash) {
    }

    /** The club an active key acts for; name and keyPrefix identify the key in audit logs. */
    public record VerifiedApiKeyDto(UUID apiKeyId, UUID clubId, String name, String keyPrefix) {
    }
}
