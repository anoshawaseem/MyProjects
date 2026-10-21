package com.micomm.tenantmgmt.provider;

import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.MicommAuthenticatedUser;
import com.micomm.tenantmgmt.audit.ClubAuditLog;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/clubs/{clubId}/provider-accounts")
public class ProviderAccountController {

    private final ProviderAccountService providerAccountService;
    private final ClubAuditLog clubAuditLog;

    public ProviderAccountController(ProviderAccountService providerAccountService, ClubAuditLog clubAuditLog) {
        this.providerAccountService = providerAccountService;
        this.clubAuditLog = clubAuditLog;
    }

    @GetMapping
    public ApiResponse<List<ProviderAccount>> list(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID clubId) {

        if (currentUser.isTenantAdmin() && !clubId.equals(currentUser.clubId())) {
            throw new SecurityException("Access denied");
        }
        return ApiResponse.ok(providerAccountService.listForClub(clubId));
    }

    @PostMapping
    public ApiResponse<ProviderAccount> create(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID clubId,
            @RequestBody CreateProviderAccountRequest request) {

        if (currentUser.isTenantAdmin() && !clubId.equals(currentUser.clubId())) {
            throw new SecurityException("Access denied");
        }
        ProviderAccount account = providerAccountService.create(
                clubId, request.providerTypeId(), request.name(), request.secretReference(), request.configJson());
        // Never the secret or the config (it can hold credentials too).
        clubAuditLog.record(AuditEvent.of(clubId, null, AuditActions.PROVIDER_ACCOUNT_CREATED, "PROVIDER_ACCOUNT",
                account.getId()).with("name", account.getName()).with("providerTypeId", account.getProviderTypeId()));
        return ApiResponse.ok(account);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID clubId,
            @PathVariable UUID id) {

        if (currentUser.isTenantAdmin() && !clubId.equals(currentUser.clubId())) {
            throw new SecurityException("Access denied");
        }
        providerAccountService.delete(id);
        clubAuditLog.record(AuditEvent.of(clubId, null, AuditActions.PROVIDER_ACCOUNT_DISABLED, "PROVIDER_ACCOUNT", id));
        return ApiResponse.ok(null);
    }

    public record CreateProviderAccountRequest(UUID providerTypeId, String name, String secretReference, String configJson) {}
}