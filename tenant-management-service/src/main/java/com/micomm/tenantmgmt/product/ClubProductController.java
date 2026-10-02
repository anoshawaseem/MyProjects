package com.micomm.tenantmgmt.product;

import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.MicommAuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/clubs/{clubId}/products")
public class ClubProductController {

    private final ClubProductService clubProductService;

    public ClubProductController(ClubProductService clubProductService) {
        this.clubProductService = clubProductService;
    }

    @GetMapping
    public ApiResponse<List<ClubProduct>> get(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID clubId) {

        if (currentUser.isTenantAdmin() && !clubId.equals(currentUser.clubId())) {
            throw new SecurityException("Access denied");
        }
        return ApiResponse.ok(clubProductService.getProductsForClub(clubId));
    }

    @PutMapping
    public ApiResponse<List<ClubProduct>> set(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID clubId,
            @RequestBody SetProductsRequest request) {

        if (!currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can manage club products");
        }
        return ApiResponse.ok(clubProductService.setProductsForClub(clubId, request.productIds()));
    }

    public record SetProductsRequest(Set<UUID> productIds) {}
}