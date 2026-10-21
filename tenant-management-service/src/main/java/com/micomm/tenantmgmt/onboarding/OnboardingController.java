package com.micomm.tenantmgmt.onboarding;

import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.dto.ApiResponse;
import com.micomm.tenantmgmt.audit.ClubAuditLog;
import com.micomm.common.security.MicommAuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/clubs/{clubId}/onboarding")
public class OnboardingController {

    private final TenantOnboardingService onboardingService;
    private final ClubAuditLog clubAuditLog;

    public OnboardingController(TenantOnboardingService onboardingService, ClubAuditLog clubAuditLog) {
        this.onboardingService = onboardingService;
        this.clubAuditLog = clubAuditLog;
    }

    @PostMapping
    public ApiResponse<List<TenantDatabase>> onboard(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID clubId) {

        if (currentUser == null || !currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can trigger tenant onboarding");
        }

        List<TenantDatabase> schemas = onboardingService.onboard(clubId);
        clubAuditLog.record(AuditEvent.of(clubId, null, AuditActions.CLUB_ONBOARDED, "CLUB", clubId)
                .with("productIds", schemas.stream().map(TenantDatabase::getProductId).toList())
                .with("schema", schemas.isEmpty() ? null : schemas.get(0).getDatabaseIdentifier()));
        return ApiResponse.ok(schemas);
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