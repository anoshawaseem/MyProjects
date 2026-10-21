package com.micomm.email.log;

import com.micomm.common.dto.ApiResponse;
import com.micomm.email.log.EmailLogDtos.EmailLogDetailDto;
import com.micomm.email.log.EmailLogDtos.EmailLogRecipientDto;
import com.micomm.email.log.EmailLogDtos.EmailLogSummaryDto;
import com.micomm.email.paging.PageDto;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Product-scoped read API over sent emails, for the portal and other services.
 * Lists default to the last 30 days and page with an opaque {@code cursor}
 * (pass back {@code nextCursor}), never OFFSET.
 */
@RestController
@RequestMapping("/api/v1/emails/logs")
public class EmailLogController {

    private final EmailLogService service;

    public EmailLogController(EmailLogService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageDto<EmailLogSummaryDto>> list(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.list(clubId, productId, from, to, cursor, limit));
    }

    @GetMapping("/{logId}")
    public ApiResponse<EmailLogDetailDto> get(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @PathVariable UUID logId) {
        return ApiResponse.ok(service.get(clubId, productId, logId));
    }

    @GetMapping("/{logId}/recipients")
    public ApiResponse<PageDto<EmailLogRecipientDto>> recipients(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @PathVariable UUID logId,
            @RequestParam(defaultValue = "false") boolean failedOnly,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.recipients(clubId, productId, logId, failedOnly, cursor, limit));
    }
}
