package com.micomm.email.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.micomm.common.security.ClubAccessGuard;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.log.EmailLogDtos.EmailLogDetailDto;
import com.micomm.email.log.EmailLogDtos.EmailLogRecipientDto;
import com.micomm.email.log.EmailLogDtos.EmailLogSummaryDto;
import com.micomm.email.log.EmailLogRepository;
import com.micomm.email.notification.NotificationDtos.NotificationDetailDto;
import com.micomm.email.notification.NotificationDtos.NotificationRecipientDto;
import com.micomm.email.notification.NotificationDtos.NotificationSummaryDto;
import com.micomm.email.paging.KeysetPaging;
import com.micomm.email.paging.KeysetPaging.Page;
import com.micomm.email.paging.KeysetPaging.Range;
import com.micomm.email.paging.KeysetPaging.TimeCursor;
import com.micomm.email.paging.PageDto;
import com.micomm.email.tenant.TenantModules;

/**
 * Channel-aware read API over sent notifications, for both tenant layouts:
 * new clubs read notifications / notification_recipients directly; legacy
 * clubs expose their existing email_logs data as channel EMAIL (read-only
 * mapping, nothing in those tables is changed). Legacy clubs have no data for
 * other channels, so those queries return empty pages.
 */
@Service
public class NotificationService {

    public static final String EMAIL = "EMAIL";
    public static final Set<String> CHANNELS = Set.of(EMAIL, "SMS", "PUSH", "WHATSAPP");

    private final NotificationRepository repository;
    private final EmailLogRepository legacyEmailRepository;
    private final TenantManagementClient tenantManagementClient;
    private final ClubAccessGuard clubAccessGuard;

    public NotificationService(NotificationRepository repository, EmailLogRepository legacyEmailRepository,
            TenantManagementClient tenantManagementClient, ClubAccessGuard clubAccessGuard) {
        this.repository = repository;
        this.legacyEmailRepository = legacyEmailRepository;
        this.tenantManagementClient = tenantManagementClient;
        this.clubAccessGuard = clubAccessGuard;
    }

    /** module: only sends made from that product module (module-aware clubs; none for older clubs). */
    public PageDto<NotificationSummaryDto> list(UUID clubId, UUID productId, String module, String channel,
            OffsetDateTime from, OffsetDateTime to, String cursor, Integer limit) {
        String ch = normalizeChannel(channel);
        String moduleCode = TenantModules.normalize(module);
        Range range = KeysetPaging.range(from, to);
        int pageSize = KeysetPaging.pageSize(limit);
        TimeCursor after = TimeCursor.decode(cursor);
        clubAccessGuard.requireClubAccess(clubId);
        TenantDatabaseDto tenant = tenantManagementClient.resolveTenantDatabase(clubId, productId, moduleCode);

        // Fetch one extra row to know whether another page exists.
        List<NotificationSummaryDto> rows;
        if (moduleCode != null && !tenant.supportsModules()) {
            rows = List.of();
        } else if (tenant.usesNotificationsLayout()) {
            if (moduleCode != null) {
                TenantModules.requireEnabled(tenant, moduleCode);
            }
            rows = repository.findSummaries(tenant, clubId, productId, moduleCode, ch, range.start(), range.end(),
                    after != null ? after.createdAt() : null, after != null ? after.id() : null, pageSize + 1);
        } else if (ch == null || EMAIL.equals(ch)) {
            rows = legacyEmailRepository.findSummaries(tenant.schemaName(), productId, range.start(), range.end(),
                    after != null ? after.createdAt() : null, after != null ? after.id() : null, pageSize + 1)
                    .stream().map(NotificationService::fromLegacy).toList();
        } else {
            rows = List.of();
        }

        Page<NotificationSummaryDto> page = KeysetPaging.page(rows, pageSize);
        return new PageDto<>(page.items(),
                TimeCursor.of(page, NotificationSummaryDto::createdAt, NotificationSummaryDto::id));
    }

    public NotificationDetailDto get(UUID clubId, UUID productId, UUID id) {
        TenantDatabaseDto tenant = resolve(clubId, productId);
        if (tenant.usesNotificationsLayout()) {
            return repository.findDetail(tenant, clubId, productId, id, null)
                    .orElseThrow(() -> new NotificationNotFoundException(id));
        }
        return legacyEmailRepository.findDetail(tenant.schemaName(), productId, id)
                .map(NotificationService::fromLegacy)
                .orElseThrow(() -> new NotificationNotFoundException(id));
    }

    public PageDto<NotificationRecipientDto> recipients(UUID clubId, UUID productId, UUID id, boolean failedOnly,
            String cursor, Integer limit) {
        int pageSize = KeysetPaging.pageSize(limit);
        UUID afterId = KeysetPaging.parseIdCursor(cursor);
        TenantDatabaseDto tenant = resolve(clubId, productId);
        String schema = tenant.schemaName();

        // Recipients carry no product_id, so scope through the parent; its created_at is the
        // recipients' partition key.
        List<NotificationRecipientDto> rows;
        if (tenant.usesNotificationsLayout()) {
            OffsetDateTime createdAt = repository.findCreatedAt(tenant, clubId, productId, id, null)
                    .orElseThrow(() -> new NotificationNotFoundException(id));
            rows = repository.findRecipients(tenant, clubId, productId, id, createdAt, failedOnly, afterId,
                    pageSize + 1);
        } else {
            OffsetDateTime createdAt = legacyEmailRepository.findLogCreatedAt(schema, productId, id)
                    .orElseThrow(() -> new NotificationNotFoundException(id));
            rows = legacyEmailRepository.findRecipients(schema, id, createdAt, failedOnly, afterId, pageSize + 1)
                    .stream().map(NotificationService::fromLegacy).toList();
        }

        Page<NotificationRecipientDto> page = KeysetPaging.page(rows, pageSize);
        return new PageDto<>(page.items(), KeysetPaging.idCursor(page, NotificationRecipientDto::id));
    }

    static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        String ch = channel.trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(ch)) {
            throw new IllegalArgumentException("'channel' must be one of " + CHANNELS);
        }
        return ch;
    }

    /** Every operation goes through here: authorize the club before touching its tenant schema. */
    private TenantDatabaseDto resolve(UUID clubId, UUID productId) {
        clubAccessGuard.requireClubAccess(clubId);
        return tenantManagementClient.resolveTenantDatabase(clubId, productId);
    }

    // Legacy email_logs.body holds the HTML body, and legacy rows have no sender.

    private static NotificationSummaryDto fromLegacy(EmailLogSummaryDto l) {
        return new NotificationSummaryDto(l.id(), l.productId(), EMAIL, null, l.subject(), l.status(),
                l.recipientCount(), l.successCount(), l.failureCount(), l.sentAt(), l.createdAt());
    }

    private static NotificationDetailDto fromLegacy(EmailLogDetailDto l) {
        return new NotificationDetailDto(l.id(), l.productId(), EMAIL, null, l.subject(), null, l.body(), null,
                l.status(), l.recipientCount(), l.successCount(), l.failureCount(), l.sentAt(), l.createdAt());
    }

    private static NotificationRecipientDto fromLegacy(EmailLogRecipientDto r) {
        return new NotificationRecipientDto(r.id(), EMAIL, r.recipient(), r.status(), null, r.errorMessage(),
                r.sentAt(), null, r.createdAt());
    }
}
