package com.micomm.tenantmgmt.provider;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.tenantmgmt.audit.ClubAuditLog;
import com.micomm.tenantmgmt.provider.ClubModuleProviderRepository.ModuleProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The modules of a club's product that send through their own provider account instead of the
 * product's (see /clubs/{clubId}/products/{productId}/providers). Modules not listed use the
 * product's provider.
 */
@RestController
@RequestMapping("/clubs/{clubId}/products/{productId}/module-providers")
public class ClubModuleProviderController {

    private final ClubModuleProviderService service;
    private final ClubAccessGuard clubAccessGuard;
    private final ClubAuditLog clubAuditLog;

    public ClubModuleProviderController(ClubModuleProviderService service, ClubAccessGuard clubAccessGuard,
            ClubAuditLog clubAuditLog) {
        this.service = service;
        this.clubAccessGuard = clubAccessGuard;
        this.clubAuditLog = clubAuditLog;
    }

    @GetMapping
    public ApiResponse<List<ModuleProvider>> list(@PathVariable UUID clubId, @PathVariable UUID productId) {
        clubAccessGuard.requireClubAccess(clubId);
        return ApiResponse.ok(service.list(clubId, productId));
    }

    @PutMapping
    public ApiResponse<List<ModuleProvider>> assign(@PathVariable UUID clubId, @PathVariable UUID productId,
            @Valid @RequestBody AssignModuleProviderRequest request) {
        clubAccessGuard.requireClubAccess(clubId);
        List<ModuleProvider> result = service.assign(clubId, productId, request.moduleCode(), request.channel(),
                request.providerAccountId());
        clubAuditLog.record(AuditEvent.of(clubId, productId, AuditActions.MODULE_PROVIDER_ASSIGNED,
                "PROVIDER_ACCOUNT", request.providerAccountId())
                .with("module", request.moduleCode()).with("channel", request.channel()));
        return ApiResponse.ok(result);
    }

    /** Puts the module back on the product's provider for this channel. */
    @DeleteMapping
    public ApiResponse<List<ModuleProvider>> clear(@PathVariable UUID clubId, @PathVariable UUID productId,
            @RequestParam String moduleCode, @RequestParam String channel) {
        clubAccessGuard.requireClubAccess(clubId);
        List<ModuleProvider> result = service.clear(clubId, productId, moduleCode, channel);
        clubAuditLog.record(AuditEvent.of(clubId, productId, AuditActions.MODULE_PROVIDER_CLEARED, "MODULE", moduleCode)
                .with("channel", channel));
        return ApiResponse.ok(result);
    }

    public record AssignModuleProviderRequest(@NotBlank String moduleCode, @NotBlank String channel,
            @NotNull UUID providerAccountId) {
    }
}
