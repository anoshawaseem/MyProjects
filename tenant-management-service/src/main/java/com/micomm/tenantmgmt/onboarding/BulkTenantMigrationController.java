package com.micomm.tenantmgmt.onboarding;

import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.MicommAuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Iterates every ACTIVE tenant database and applies any pending Flyway
 * migrations. Use this after adding a new db/migration/tenant/V{n}__*.sql
 * file to roll it out platform-wide.
 */
@RestController
@RequestMapping("/admin/tenant-migrations")
public class BulkTenantMigrationController {

    private static final Logger log = LoggerFactory.getLogger(BulkTenantMigrationController.class);

    private final TenantDatabaseRepository tenantDatabaseRepository;
    private final TenantOnboardingService onboardingService;

    public BulkTenantMigrationController(TenantDatabaseRepository tenantDatabaseRepository,
                                          TenantOnboardingService onboardingService) {
        this.tenantDatabaseRepository = tenantDatabaseRepository;
        this.onboardingService = onboardingService;
    }

    @PostMapping("/apply-all")
    public ApiResponse<List<String>> applyToAllTenants(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser) {

        if (!currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can run platform-wide migrations");
        }

        List<TenantDatabase> activeTenants = tenantDatabaseRepository.findAll().stream()
                .filter(t -> "ACTIVE".equals(t.getStatus()))
                .toList();

        List<String> results = new ArrayList<>();

        for (TenantDatabase tenant : activeTenants) {
            try {
                onboardingService.applyPendingMigrations(tenant.getDatabaseIdentifier());
                results.add(tenant.getDatabaseIdentifier() + ": OK");
            } catch (Exception ex) {
                log.error("Migration failed for {}: {}", tenant.getDatabaseIdentifier(), ex.getMessage(), ex);
                results.add(tenant.getDatabaseIdentifier() + ": FAILED - " + ex.getMessage());
            }
        }

        return ApiResponse.ok(results);
    }
}