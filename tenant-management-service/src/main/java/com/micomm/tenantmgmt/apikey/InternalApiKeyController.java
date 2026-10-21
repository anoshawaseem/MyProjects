package com.micomm.tenantmgmt.apikey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.common.dto.ApiResponse;
import com.micomm.tenantmgmt.apikey.ApiKeyDtos.VerifiedApiKeyDto;
import com.micomm.tenantmgmt.apikey.ApiKeyDtos.VerifyApiKeyRequest;

import jakarta.validation.Valid;

/**
 * Internal-only: lets email-service check an API key it was given. It sends the key's hash, so
 * the key itself never travels between services.
 */
@RestController
@RequestMapping("/internal/api-keys")
public class InternalApiKeyController {

    private final ApiKeyService apiKeyService;

    @Value("${micomm.internal.api-key}")
    private String internalApiKey;

    public InternalApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /** data is null when the key is unknown, revoked or expired, or its club is not active. */
    @PostMapping("/verify")
    public ApiResponse<VerifiedApiKeyDto> verify(
            @RequestHeader("X-Internal-Api-Key") String apiKey,
            @Valid @RequestBody VerifyApiKeyRequest request) {
        if (!internalApiKey.equals(apiKey)) {
            throw new AccessDeniedException("Invalid internal API key");
        }
        return ApiResponse.ok(apiKeyService.verify(request.keyHash()).orElse(null));
    }
}
