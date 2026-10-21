package com.micomm.tenantmgmt.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.tenantmgmt.audit.AuditLogQueryService.AuditLogPageDto;

/** A club's audit log: readable by its TENANT_ADMIN and by ORG_ADMIN. Entries cannot be changed or deleted. */
@RestController
@RequestMapping("/clubs/{clubId}/audit-logs")
public class ClubAuditLogController {

    private final AuditLogQueryService queryService;
    private final ClubAccessGuard clubAccessGuard;

    public ClubAuditLogController(AuditLogQueryService queryService, ClubAccessGuard clubAccessGuard) {
        this.queryService = queryService;
        this.clubAccessGuard = clubAccessGuard;
    }

    /** from (inclusive) / to (exclusive) are ISO date-times; action is e.g. EMAIL_SEND_QUEUED. */
    @GetMapping
    public ApiResponse<AuditLogPageDto> list(
            @PathVariable UUID clubId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        clubAccessGuard.requireClubAccess(clubId);
        return ApiResponse.ok(queryService.list(clubId, productId, action, from, to, cursor, limit));
    }
}
