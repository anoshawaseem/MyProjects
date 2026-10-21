package com.micomm.tenantmgmt.onboarding;

import com.micomm.common.dto.ApiResponse;
import com.micomm.tenantmgmt.club.Club;
import com.micomm.tenantmgmt.club.ClubRepository;
import com.micomm.tenantmgmt.module.ModuleService;
import com.micomm.tenantmgmt.product.Product;
import com.micomm.tenantmgmt.product.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private final ModuleService moduleService;
    private final ClubRepository clubRepository;
    private final ProductRepository productRepository;

    @Value("${micomm.internal.api-key}")
    private String internalApiKey;

    public InternalTenantDatabaseController(TenantDatabaseRepository tenantDatabaseRepository,
            ModuleService moduleService, ClubRepository clubRepository, ProductRepository productRepository) {
        this.tenantDatabaseRepository = tenantDatabaseRepository;
        this.moduleService = moduleService;
        this.clubRepository = clubRepository;
        this.productRepository = productRepository;
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

        // Modules exist only in club-scoped schemas; older layouts never report any.
        List<String> modules = TenantSchemaLayout.CLUB_SCOPED.version().equals(db.getSchemaVersion())
                ? moduleService.enabledModuleCodes(clubId, productId)
                : List.of();
        String clubName = clubRepository.findById(clubId).map(Club::getName).orElse(null);
        String productName = productRepository.findById(productId).map(Product::getName).orElse(null);
        return ApiResponse.ok(new TenantDatabaseDto(db.getDatabaseIdentifier(), db.getSchemaVersion(), modules,
                clubName, productName));
    }
}