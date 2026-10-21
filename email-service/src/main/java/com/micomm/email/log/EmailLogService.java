package com.micomm.email.log;

import com.micomm.common.security.ClubAccessGuard;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.log.EmailLogDtos.EmailLogDetailDto;
import com.micomm.email.log.EmailLogDtos.EmailLogRecipientDto;
import com.micomm.email.log.EmailLogDtos.EmailLogSummaryDto;
import com.micomm.email.notification.NotificationDtos.NotificationDetailDto;
import com.micomm.email.notification.NotificationDtos.NotificationRecipientDto;
import com.micomm.email.notification.NotificationDtos.NotificationSummaryDto;
import com.micomm.email.notification.NotificationRepository;
import com.micomm.email.notification.NotificationService;
import com.micomm.email.paging.KeysetPaging;
import com.micomm.email.paging.KeysetPaging.Page;
import com.micomm.email.paging.KeysetPaging.Range;
import com.micomm.email.paging.KeysetPaging.TimeCursor;
import com.micomm.email.paging.PageDto;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Email-only read API kept for existing callers (/api/v1/emails/logs). Legacy
 * clubs read email_logs; clubs on the notifications layout read notifications
 * with channel = EMAIL, mapped to the same response shape.
 */
@Service
public class EmailLogService {

    private static final String EMAIL = NotificationService.EMAIL;

    private final EmailLogRepository repository;
    private final NotificationRepository notificationRepository;
    private final TenantManagementClient tenantManagementClient;
    private final ClubAccessGuard clubAccessGuard;

    public EmailLogService(EmailLogRepository repository, NotificationRepository notificationRepository,
            TenantManagementClient tenantManagementClient, ClubAccessGuard clubAccessGuard) {
        this.repository = repository;
        this.notificationRepository = notificationRepository;
        this.tenantManagementClient = tenantManagementClient;
        this.clubAccessGuard = clubAccessGuard;
    }

    public PageDto<EmailLogSummaryDto> list(UUID clubId, UUID productId, OffsetDateTime from, OffsetDateTime to,
            String cursor, Integer limit) {
        Range range = KeysetPaging.range(from, to);
        int pageSize = KeysetPaging.pageSize(limit);
        TimeCursor after = TimeCursor.decode(cursor);
        TenantDatabaseDto tenant = resolve(clubId, productId);

        // Fetch one extra row to know whether another page exists.
        List<EmailLogSummaryDto> rows = tenant.usesNotificationsLayout()
                ? notificationRepository.findSummaries(tenant, clubId, productId, null, EMAIL, range.start(),
                        range.end(), after != null ? after.createdAt() : null, after != null ? after.id() : null,
                        pageSize + 1).stream().map(EmailLogService::toEmailLog).toList()
                : repository.findSummaries(tenant.schemaName(), productId, range.start(), range.end(),
                        after != null ? after.createdAt() : null, after != null ? after.id() : null, pageSize + 1);

        Page<EmailLogSummaryDto> page = KeysetPaging.page(rows, pageSize);
        return new PageDto<>(page.items(), TimeCursor.of(page, EmailLogSummaryDto::createdAt, EmailLogSummaryDto::id));
    }

    public EmailLogDetailDto get(UUID clubId, UUID productId, UUID logId) {
        TenantDatabaseDto tenant = resolve(clubId, productId);
        if (tenant.usesNotificationsLayout()) {
            return notificationRepository.findDetail(tenant, clubId, productId, logId, EMAIL)
                    .map(EmailLogService::toEmailLog)
                    .orElseThrow(() -> new EmailLogNotFoundException(logId));
        }
        return repository.findDetail(tenant.schemaName(), productId, logId)
                .orElseThrow(() -> new EmailLogNotFoundException(logId));
    }

    public PageDto<EmailLogRecipientDto> recipients(UUID clubId, UUID productId, UUID logId, boolean failedOnly,
            String cursor, Integer limit) {
        int pageSize = KeysetPaging.pageSize(limit);
        UUID afterId = KeysetPaging.parseIdCursor(cursor);
        TenantDatabaseDto tenant = resolve(clubId, productId);
        String schema = tenant.schemaName();

        // Recipients carry no product_id, so scope through the parent log; its created_at
        // is the recipients' partition key.
        List<EmailLogRecipientDto> rows;
        if (tenant.usesNotificationsLayout()) {
            OffsetDateTime createdAt = notificationRepository.findCreatedAt(tenant, clubId, productId, logId, EMAIL)
                    .orElseThrow(() -> new EmailLogNotFoundException(logId));
            rows = notificationRepository.findRecipients(tenant, clubId, productId, logId, createdAt, failedOnly,
                    afterId, pageSize + 1)
                    .stream().map(EmailLogService::toEmailLog).toList();
        } else {
            OffsetDateTime createdAt = repository.findLogCreatedAt(schema, productId, logId)
                    .orElseThrow(() -> new EmailLogNotFoundException(logId));
            rows = repository.findRecipients(schema, logId, createdAt, failedOnly, afterId, pageSize + 1);
        }

        Page<EmailLogRecipientDto> page = KeysetPaging.page(rows, pageSize);
        return new PageDto<>(page.items(), KeysetPaging.idCursor(page, EmailLogRecipientDto::id));
    }

    /** Every operation goes through here: authorize the club before touching its tenant schema. */
    private TenantDatabaseDto resolve(UUID clubId, UUID productId) {
        clubAccessGuard.requireClubAccess(clubId);
        return tenantManagementClient.resolveTenantDatabase(clubId, productId);
    }

    // Email sends store the HTML in html_body; "body" in this API has always been the HTML.

    private static EmailLogSummaryDto toEmailLog(NotificationSummaryDto n) {
        return new EmailLogSummaryDto(n.id(), n.productId(), n.subject(), n.status(), n.recipientCount(),
                n.successCount(), n.failureCount(), n.sentAt(), n.createdAt());
    }

    private static EmailLogDetailDto toEmailLog(NotificationDetailDto n) {
        return new EmailLogDetailDto(n.id(), n.productId(), n.subject(),
                n.htmlBody() != null ? n.htmlBody() : n.body(), n.status(), n.recipientCount(), n.successCount(),
                n.failureCount(), n.sentAt(), n.createdAt());
    }

    private static EmailLogRecipientDto toEmailLog(NotificationRecipientDto r) {
        return new EmailLogRecipientDto(r.id(), r.address(), r.status(), r.errorMessage(), r.sentAt(),
                r.createdAt());
    }
}
