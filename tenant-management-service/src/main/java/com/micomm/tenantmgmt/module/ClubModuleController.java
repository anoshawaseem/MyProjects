package com.micomm.tenantmgmt.module;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.dto.ApiResponse;
import com.micomm.tenantmgmt.audit.ClubAuditLog;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.common.security.MicommAuthenticatedUser;
import com.micomm.tenantmgmt.module.ModuleDtos.ClubModulesDto;
import com.micomm.tenantmgmt.module.ModuleDtos.SetClubModulesRequest;

import jakarta.validation.Valid;

/** Which modules a club has switched on for one of its products. */
@RestController
@RequestMapping("/clubs/{clubId}/products/{productId}/modules")
public class ClubModuleController {

    private final ModuleService moduleService;
    private final ClubAccessGuard clubAccessGuard;
    private final ClubAuditLog clubAuditLog;

    public ClubModuleController(ModuleService moduleService, ClubAccessGuard clubAccessGuard,
            ClubAuditLog clubAuditLog) {
        this.moduleService = moduleService;
        this.clubAccessGuard = clubAccessGuard;
        this.clubAuditLog = clubAuditLog;
    }

    @GetMapping
    public ApiResponse<ClubModulesDto> get(@PathVariable UUID clubId, @PathVariable UUID productId) {
        clubAccessGuard.requireClubAccess(clubId);
        return ApiResponse.ok(moduleService.clubModules(clubId, productId));
    }

    /** Replaces the enabled set; ORG_ADMIN only, like managing the club's products. */
    @PutMapping
    public ApiResponse<ClubModulesDto> set(@PathVariable UUID clubId, @PathVariable UUID productId,
            @Valid @RequestBody SetClubModulesRequest request) {
        MicommAuthenticatedUser user = clubAccessGuard.requireClubAccess(clubId);
        if (!user.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can manage club modules");
        }
        ClubModulesDto result = moduleService.setClubModules(clubId, productId, request.moduleCodes());
        clubAuditLog.record(AuditEvent.of(clubId, productId, AuditActions.MODULES_UPDATED, "PRODUCT", productId)
                .with("enabledModules", request.moduleCodes()));
        return ApiResponse.ok(result);
    }
}
