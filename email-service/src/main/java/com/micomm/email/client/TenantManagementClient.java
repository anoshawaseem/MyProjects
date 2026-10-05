package com.micomm.email.client;

import com.micomm.email.client.dto.ResolvedProviderDto;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.common.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class TenantManagementClient {

    private final RestClient restClient;
    private final String internalApiKey;

    public TenantManagementClient(
            @Value("${micomm.internal.tenant-management-base-url}") String baseUrl,
            @Value("${micomm.internal.api-key}") String internalApiKey) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalApiKey = internalApiKey;
    }

    public TenantDatabaseDto resolveTenantDatabase(UUID clubId, UUID productId) {
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

    public ResolvedProviderDto resolveProvider(UUID clubId, UUID productId, String channel) {
        ApiResponse<ResolvedProviderDto> response = restClient.get()
                .uri("/internal/clubs/{clubId}/products/{productId}/providers/{channel}", clubId, productId, channel)
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