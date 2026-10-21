package com.micomm.email.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.micomm.email.client.dto.ResolvedProviderDto;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.client.dto.VerifiedApiKeyDto;
import com.micomm.common.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves tenant schema / provider details from tenant-management-service.
 *
 * Results are cached in-process for {@code micomm.internal.tenant-cache-ttl}
 * (default 5 minutes) so sends and reads don't pay an HTTP round-trip per
 * request. Failed lookups are never cached. A product removal or provider
 * change therefore takes up to one TTL to be picked up by this service.
 */
@Component
public class TenantManagementClient {

    private static final int MAX_CACHED_ENTRIES = 10_000;

    private final RestClient restClient;
    private final String internalApiKey;
    private final Cache<TenantKey, TenantDatabaseDto> tenantDatabaseCache;
    private final Cache<ProviderKey, ResolvedProviderDto> providerCache;
    private final Cache<String, Optional<VerifiedApiKeyDto>> apiKeyCache;
    // Short-lived, so a flood of wrong keys does not turn into a flood of verify calls.
    private final Cache<String, Boolean> unknownApiKeyCache;

    private record TenantKey(UUID clubId, UUID productId) {
    }

    /** moduleCode is null for sends from no module. */
    private record ProviderKey(UUID clubId, UUID productId, String channel, String moduleCode) {
    }

    public TenantManagementClient(
            @Value("${micomm.internal.tenant-management-base-url}") String baseUrl,
            @Value("${micomm.internal.api-key}") String internalApiKey,
            @Value("${micomm.internal.tenant-cache-ttl:5m}") Duration cacheTtl,
            @Value("${micomm.internal.api-key-cache-ttl:1m}") Duration apiKeyCacheTtl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalApiKey = internalApiKey;
        this.tenantDatabaseCache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtl)
                .maximumSize(MAX_CACHED_ENTRIES)
                .build();
        this.providerCache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtl)
                .maximumSize(MAX_CACHED_ENTRIES)
                .build();
        this.apiKeyCache = Caffeine.newBuilder()
                .expireAfterWrite(apiKeyCacheTtl)
                .maximumSize(MAX_CACHED_ENTRIES)
                .build();
        this.unknownApiKeyCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(MAX_CACHED_ENTRIES)
                .build();
    }

    public TenantDatabaseDto resolveTenantDatabase(UUID clubId, UUID productId) {
        return tenantDatabaseCache.get(new TenantKey(clubId, productId),
                key -> fetchTenantDatabase(key.clubId(), key.productId()));
    }

    /**
     * Like {@link #resolveTenantDatabase(UUID, UUID)}, but when the cached entry does not list
     * {@code moduleCode} as enabled, re-fetches once: a module switched on moments ago must work
     * immediately, not after the cache TTL. (A module switched off can stay usable for up to one
     * TTL.) A null moduleCode never re-fetches.
     */
    public TenantDatabaseDto resolveTenantDatabase(UUID clubId, UUID productId, String moduleCode) {
        TenantDatabaseDto tenant = resolveTenantDatabase(clubId, productId);
        if (moduleCode == null || !tenant.supportsModules() || tenant.moduleEnabled(moduleCode)) {
            return tenant;
        }
        tenantDatabaseCache.invalidate(new TenantKey(clubId, productId));
        return resolveTenantDatabase(clubId, productId);
    }

    /**
     * The club an API key acts for, or empty if the key is unknown, revoked or expired. Takes the
     * key's SHA-256 hash, so the key itself is never sent on. Answers are cached for
     * {@code micomm.internal.api-key-cache-ttl}: a revoked key keeps working for up to that long,
     * and a newly created one is rejected for up to the (shorter) negative TTL if it was tried
     * before it existed.
     */
    public Optional<VerifiedApiKeyDto> verifyApiKey(String keyHash) {
        Optional<VerifiedApiKeyDto> cached = apiKeyCache.getIfPresent(keyHash);
        if (cached != null) {
            return cached;
        }
        if (unknownApiKeyCache.getIfPresent(keyHash) != null) {
            return Optional.empty();
        }
        ApiResponse<VerifiedApiKeyDto> response = restClient.post()
                .uri("/internal/api-keys/verify")
                .header("X-Internal-Api-Key", internalApiKey)
                .body(Map.of("keyHash", keyHash))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<VerifiedApiKeyDto>>() {});
        if (response == null || !response.success()) {
            throw new TenantResolutionException(
                    response != null ? response.errorMessage() : "Empty response verifying API key");
        }
        if (response.data() == null) {
            unknownApiKeyCache.put(keyHash, Boolean.TRUE);
            return Optional.empty();
        }
        Optional<VerifiedApiKeyDto> verified = Optional.of(response.data());
        apiKeyCache.put(keyHash, verified);
        return verified;
    }

    public ResolvedProviderDto resolveProvider(UUID clubId, UUID productId, String channel) {
        return resolveProvider(clubId, productId, channel, null);
    }

    /**
     * The provider for a send from {@code moduleCode}: the module's own provider account if the
     * club set one, otherwise the product's. A null moduleCode always gets the product's.
     */
    public ResolvedProviderDto resolveProvider(UUID clubId, UUID productId, String channel, String moduleCode) {
        return providerCache.get(new ProviderKey(clubId, productId, channel, moduleCode),
                key -> fetchProvider(key.clubId(), key.productId(), key.channel(), key.moduleCode()));
    }

    /** Drops cached entries for a club/product, e.g. after a failure caused by a stale schema. */
    public void evict(UUID clubId, UUID productId) {
        tenantDatabaseCache.invalidate(new TenantKey(clubId, productId));
        providerCache.asMap().keySet().removeIf(k -> k.clubId().equals(clubId) && k.productId().equals(productId));
    }

    private TenantDatabaseDto fetchTenantDatabase(UUID clubId, UUID productId) {
        ApiResponse<TenantDatabaseDto> response = restClient.get()
                .uri("/internal/clubs/{clubId}/products/{productId}/database", clubId, productId)
                .header("X-Internal-Api-Key", internalApiKey)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<TenantDatabaseDto>>() {});

        if (response == null || !response.success()) {
            throw new TenantResolutionException(
                    response != null ? response.errorMessage() : "Empty response resolving tenant database");
        }
        return response.data();
    }

    private ResolvedProviderDto fetchProvider(UUID clubId, UUID productId, String channel, String moduleCode) {
        ApiResponse<ResolvedProviderDto> response = restClient.get()
                .uri(uri -> uri.path("/internal/clubs/{clubId}/products/{productId}/providers/{channel}")
                        .queryParamIfPresent("module", Optional.ofNullable(moduleCode))
                        .build(clubId, productId, channel))
                .header("X-Internal-Api-Key", internalApiKey)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<ResolvedProviderDto>>() {});

        if (response == null || !response.success()) {
            throw new TenantResolutionException(
                    response != null ? response.errorMessage() : "Empty response resolving provider");
        }
        return response.data();
    }
}
