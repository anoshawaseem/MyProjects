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
@RequestMapping("/clubs/{clubId}/products/{productId}/providers")
public class ClubProductProviderController {

    private final ClubProductProviderService service;
    private final ClubAuditLog clubAuditLog;

    public ClubProductProviderController(ClubProductProviderService service, ClubAuditLog clubAuditLog) {
        this.service = service;
        this.clubAuditLog = clubAuditLog;
    }

    @GetMapping
    public ApiResponse<List<ClubProductProvider>> get(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID clubId,
            @PathVariable UUID productId) {

        if (currentUser.isTenantAdmin() && !clubId.equals(currentUser.clubId())) {
            throw new SecurityException("Access denied");
        }
        return ApiResponse.ok(service.getForClubProduct(clubId, productId));
    }

    @PutMapping
    public ApiResponse<ClubProductProvider> assign(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID clubId,
            @PathVariable UUID productId,
            @RequestBody AssignProviderRequest request) {

        if (currentUser.isTenantAdmin() && !clubId.equals(currentUser.clubId())) {
            throw new SecurityException("Access denied");
        }
        ClubProductProvider mapping = service.assign(clubId, productId, request.channel(), request.providerAccountId());
        clubAuditLog.record(AuditEvent.of(clubId, productId, AuditActions.PRODUCT_PROVIDER_ASSIGNED, "PROVIDER_ACCOUNT",
                request.providerAccountId()).with("channel", request.channel()));
        return ApiResponse.ok(mapping);
    }

    public record AssignProviderRequest(String channel, UUID providerAccountId) {}
}