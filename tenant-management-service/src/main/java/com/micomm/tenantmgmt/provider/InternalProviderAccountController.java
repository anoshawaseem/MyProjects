package com.micomm.tenantmgmt.provider;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.common.dto.ApiResponse;

/**
 * Internal-only endpoint used by email-service / sms-service to resolve
 * the configured provider for a club+product+channel, including the
 * actual secret. Must never be exposed to the public frontend.
 *
 * Protected via a shared internal API key header (X-Internal-Api-Key).
 */
@RestController
@RequestMapping("/internal/clubs/{clubId}/products/{productId}/providers")
public class InternalProviderAccountController {

    private final ProviderResolutionService resolutionService;

    @Value("${micomm.internal.api-key}")
    private String internalApiKey;

    public InternalProviderAccountController(ProviderResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    @GetMapping("/{channel}")
    public ApiResponse<ResolvedProviderDto> resolve(
            @RequestHeader("X-Internal-Api-Key") String apiKey,
            @PathVariable UUID clubId,
            @PathVariable UUID productId,
            @PathVariable String channel) {

        if (!internalApiKey.equals(apiKey)) {
            throw new AccessDeniedException("Invalid internal API key");
        }

        return ApiResponse.ok(resolutionService.resolve(clubId, productId, channel));
    }
}