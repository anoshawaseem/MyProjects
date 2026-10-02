package com.micomm.tenantmgmt.onboarding;

import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.MicommAuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/clubs/{clubId}/onboarding")
public class OnboardingController {

    private final TenantOnboardingService onboardingService;

    public OnboardingController(TenantOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping
    public ApiResponse<List<TenantDatabase>> onboard(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID clubId) {

        if (currentUser == null || !currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can trigger tenant onboarding");
        }

        return ApiResponse.ok(onboardingService.onboard(clubId));
    }

    @PostMapping("/migrate")
    public ApiResponse<String> migrate(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID clubId,
            @RequestParam String databaseIdentifier) {

        if (currentUser == null || !currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can trigger migrations");
        }

        String version = onboardingService.applyPendingMigrations(databaseIdentifier);
        return ApiResponse.ok("Migrations applied to " + databaseIdentifier + " (version " + version + ")");
    }
}