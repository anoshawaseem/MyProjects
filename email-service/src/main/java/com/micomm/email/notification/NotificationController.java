package com.micomm.email.notification;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.common.dto.ApiResponse;
import com.micomm.email.notification.NotificationDtos.NotificationDetailDto;
import com.micomm.email.notification.NotificationDtos.NotificationRecipientDto;
import com.micomm.email.notification.NotificationDtos.NotificationSummaryDto;
import com.micomm.email.paging.PageDto;

/**
 * Channel-aware, product-scoped read API over sent notifications, for the
 * portal and other services. {@code channel} (EMAIL, SMS, PUSH, WHATSAPP) is
 * optional; lists default to the last 30 days and page with an opaque
 * {@code cursor} (pass back {@code nextCursor}), never OFFSET.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageDto<NotificationSummaryDto>> list(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.list(clubId, productId, module, channel, from, to, cursor, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<NotificationDetailDto> get(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @PathVariable UUID id) {
        return ApiResponse.ok(service.get(clubId, productId, id));
    }

    @GetMapping("/{id}/recipients")
    public ApiResponse<PageDto<NotificationRecipientDto>> recipients(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean failedOnly,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.recipients(clubId, productId, id, failedOnly, cursor, limit));
    }
}
