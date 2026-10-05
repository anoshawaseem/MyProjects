package com.micomm.tenantmgmt.onboarding;

import com.micomm.common.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Internal-only endpoint used by email-service / sms-service to resolve
 * the tenant schema name for a given club+product, so they can connect
 * to the shared platform database and SET search_path accordingly.
 */
@RestController
@RequestMapping("/internal/clubs/{clubId}/products/{productId}/database")
public class InternalTenantDatabaseController {

    private final TenantDatabaseRepository tenantDatabaseRepository;

    @Value("${micomm.internal.api-key}")
    private String internalApiKey;

    public InternalTenantDatabaseController(TenantDatabaseRepository tenantDatabaseRepository) {
        this.tenantDatabaseRepository = tenantDatabaseRepository;
    }

    @GetMapping
    public ApiResponse<TenantDatabaseDto> resolve(
            @RequestHeader("X-Internal-Api-Key") String apiKey,
            @PathVariable UUID clubId,
            @PathVariable UUID productId) {

        if (!internalApiKey.equals(apiKey)) {
            throw new AccessDeniedException("Invalid internal API key");
        }

        TenantDatabase db = tenantDatabaseRepository.findByClubIdAndProductId(clubId, productId)
                .orElseThrow(() -> new TenantOnboardingException(
                        "No tenant schema provisioned for club " + clubId + " / product " + productId, clubId));

        if (!"ACTIVE".equals(db.getStatus())) {
            throw new TenantOnboardingException("Tenant schema is not active", clubId);
        }

        return ApiResponse.ok(new TenantDatabaseDto(db.getDatabaseIdentifier(), db.getSchemaVersion()));
    }
}