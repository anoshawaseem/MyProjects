package com.micomm.tenantmgmt.product;

import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.dto.ApiResponse;
import com.micomm.tenantmgmt.audit.ClubAuditLog;
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
    private final ClubAuditLog clubAuditLog;

    public ClubProductController(ClubProductService clubProductService, ClubAuditLog clubAuditLog) {
        this.clubProductService = clubProductService;
        this.clubAuditLog = clubAuditLog;
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
        List<ClubProduct> products = clubProductService.setProductsForClub(clubId, request.productIds());
        clubAuditLog.record(AuditEvent.of(clubId, null, AuditActions.CLUB_PRODUCTS_UPDATED, "CLUB", clubId)
                .with("productIds", request.productIds()));
        return ApiResponse.ok(products);
    }

    public record SetProductsRequest(Set<UUID> productIds) {}
}