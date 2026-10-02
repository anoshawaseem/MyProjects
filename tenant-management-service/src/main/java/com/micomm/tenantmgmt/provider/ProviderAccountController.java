package com.micomm.tenantmgmt.provider;

import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.MicommAuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/clubs/{clubId}/provider-accounts")
public class ProviderAccountController {

    private final ProviderAccountService providerAccountService;

    public ProviderAccountController(ProviderAccountService providerAccountService) {
        this.providerAccountService = providerAccountService;
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
        return ApiResponse.ok(providerAccountService.create(
                clubId, request.providerTypeId(), request.name(), request.secretReference(), request.configJson()));
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
        return ApiResponse.ok(null);
    }

    public record CreateProviderAccountRequest(UUID providerTypeId, String name, String secretReference, String configJson) {}
}