package com.micomm.tenantmgmt.provider;

import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.MicommAuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/clubs/{clubId}/products/{productId}/providers")
public class ClubProductProviderController {

    private final ClubProductProviderService service;

    public ClubProductProviderController(ClubProductProviderService service) {
        this.service = service;
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
        return ApiResponse.ok(service.assign(clubId, productId, request.channel(), request.providerAccountId()));
    }

    public record AssignProviderRequest(String channel, UUID providerAccountId) {}
}