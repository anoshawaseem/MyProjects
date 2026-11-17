package com.micomm.tenantmgmt.oauth;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.common.security.MicommAuthenticatedUser;
import com.micomm.tenantmgmt.audit.ClubAuditLog;
import com.micomm.tenantmgmt.oauth.OAuthClientDtos.OAuthClientDto;
import com.micomm.tenantmgmt.oauth.OAuthClientDtos.RegisterClientRequest;
import com.micomm.tenantmgmt.oauth.OAuthClientService.InvalidClientRegistrationException;

import jakarta.validation.Valid;

/**
 * A club's OAuth applications, managed by its TENANT_ADMIN or an ORG_ADMIN signed in as a user.
 * Applications' own tokens are not accepted by this service, so an application can never
 * register or revoke applications.
 */
@RestController
@RequestMapping("/clubs/{clubId}/oauth-clients")
public class ClubOAuthClientController {

    private final OAuthClientService service;
    private final ClubAccessGuard clubAccessGuard;
    private final ClubAuditLog clubAuditLog;

    public ClubOAuthClientController(OAuthClientService service, ClubAccessGuard clubAccessGuard,
            ClubAuditLog clubAuditLog) {
        this.service = service;
        this.clubAccessGuard = clubAccessGuard;
        this.clubAuditLog = clubAuditLog;
    }

    @GetMapping
    public ApiResponse<List<OAuthClientDto>> list(@PathVariable UUID clubId) {
        clubAccessGuard.requireClubAccess(clubId);
        return ApiResponse.ok(service.list(clubId));
    }

    @PostMapping
    public ApiResponse<OAuthClientDto> register(@PathVariable UUID clubId,
            @Valid @RequestBody RegisterClientRequest request) {
        MicommAuthenticatedUser user = clubAccessGuard.requireClubAccess(clubId);
        OAuthClientDto client = service.register(clubId, request, user.userId());
        clubAuditLog.record(AuditEvent.of(clubId, null, AuditActions.OAUTH_CLIENT_REGISTERED, "OAUTH_CLIENT",
                client.id()).with("name", client.name()).with("clientId", client.clientId())
                .with("scopes", client.scopes()).with("keyAlgorithm", client.keyAlgorithm()));
        return ApiResponse.ok(client);
    }

    /** Revoked applications get no new tokens; tokens already issued expire within their lifetime. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable UUID clubId, @PathVariable UUID id) {
        clubAccessGuard.requireClubAccess(clubId);
        service.revoke(clubId, id);
        clubAuditLog.record(AuditEvent.of(clubId, null, AuditActions.OAUTH_CLIENT_REVOKED, "OAUTH_CLIENT", id));
        return ApiResponse.ok(null);
    }

    @ExceptionHandler(InvalidClientRegistrationException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(InvalidClientRegistrationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(ex.getMessage()));
    }
}
