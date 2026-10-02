package com.micomm.tenantmgmt.club;

import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.MicommAuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/clubs")
public class ClubController {

    private final ClubService clubService;

    public ClubController(ClubService clubService) {
        this.clubService = clubService;
    }

    @PostMapping
    public ApiResponse<Club> create(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @Valid @RequestBody ClubCreateRequest request) {

        if (!currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can create clubs");
        }
        return ApiResponse.ok(clubService.create(request));
    }

    @GetMapping
    public ApiResponse<List<Club>> list(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser) {

        if (!currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can list clubs");
        }
        return ApiResponse.ok(clubService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<Club> get(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID id) {

        if (currentUser.isTenantAdmin() && !id.equals(currentUser.clubId())) {
            throw new SecurityException("Access denied");
        }
        return ApiResponse.ok(clubService.findById(id));
    }
}