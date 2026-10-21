package com.micomm.tenantmgmt.apikey;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.micomm.tenantmgmt.audit.ClubAuditLog;
import com.micomm.common.security.MicommAuthenticatedUser;
import com.micomm.tenantmgmt.apikey.ApiKeyDtos.ApiKeyDto;
import com.micomm.tenantmgmt.apikey.ApiKeyDtos.CreateApiKeyRequest;
import com.micomm.tenantmgmt.apikey.ApiKeyDtos.CreatedApiKeyDto;

import jakarta.validation.Valid;

/**
 * A club's API keys, managed by its TENANT_ADMIN or an ORG_ADMIN signed in as a user. API keys
 * themselves are only accepted by email-service, so a key can never create or revoke keys.
 */
@RestController
@RequestMapping("/clubs/{clubId}/api-keys")
public class ClubApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ClubAccessGuard clubAccessGuard;
    private final ClubAuditLog clubAuditLog;

    public ClubApiKeyController(ApiKeyService apiKeyService, ClubAccessGuard clubAccessGuard,
            ClubAuditLog clubAuditLog) {
        this.apiKeyService = apiKeyService;
        this.clubAccessGuard = clubAccessGuard;
        this.clubAuditLog = clubAuditLog;
    }

    @GetMapping
    public ApiResponse<List<ApiKeyDto>> list(@PathVariable UUID clubId) {
        clubAccessGuard.requireClubAccess(clubId);
        return ApiResponse.ok(apiKeyService.list(clubId));
    }

    /** The response holds the full key; it is not stored and cannot be shown again. */
    @PostMapping
    public ApiResponse<CreatedApiKeyDto> create(@PathVariable UUID clubId,
            @Valid @RequestBody CreateApiKeyRequest request) {
        MicommAuthenticatedUser user = clubAccessGuard.requireClubAccess(clubId);
        CreatedApiKeyDto created = apiKeyService.create(clubId, request, user.userId());
        // The key's public prefix only, never the key.
        clubAuditLog.record(AuditEvent.of(clubId, null, AuditActions.API_KEY_CREATED, "API_KEY", created.apiKey().id())
                .with("name", created.apiKey().name())
                .with("keyPrefix", created.apiKey().keyPrefix())
                .with("expiresAt", created.apiKey().expiresAt() != null ? created.apiKey().expiresAt().toString() : null));
        return ApiResponse.ok(created);
    }

    /** Revoking takes effect in email-service within its key cache time (one minute by default). */
    @DeleteMapping("/{apiKeyId}")
    public ApiResponse<Void> revoke(@PathVariable UUID clubId, @PathVariable UUID apiKeyId) {
        clubAccessGuard.requireClubAccess(clubId);
        apiKeyService.revoke(clubId, apiKeyId);
        clubAuditLog.record(AuditEvent.of(clubId, null, AuditActions.API_KEY_REVOKED, "API_KEY", apiKeyId));
        return ApiResponse.ok(null);
    }
}
