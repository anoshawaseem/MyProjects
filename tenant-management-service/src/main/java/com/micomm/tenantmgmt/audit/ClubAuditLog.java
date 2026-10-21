package com.micomm.tenantmgmt.audit;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.audit.AuditLogWriter;
import com.micomm.tenantmgmt.onboarding.TenantDatabase;
import com.micomm.tenantmgmt.onboarding.TenantDatabaseRepository;
import com.micomm.tenantmgmt.onboarding.TenantSchemaLayout;

/**
 * Records audit events in the club's own schema. A club has one schema shared by all its
 * products; until it is onboarded it has none, so its earlier setup steps (creating the club,
 * choosing its first products) cannot be recorded there. Never throws.
 */
@Service
public class ClubAuditLog {

    private static final Logger log = LoggerFactory.getLogger(ClubAuditLog.class);

    private final TenantDatabaseRepository tenantDatabaseRepository;
    private final AuditLogWriter writer;

    public ClubAuditLog(TenantDatabaseRepository tenantDatabaseRepository, PlatformDatabase platformDatabase,
            ObjectMapper objectMapper) {
        this.tenantDatabaseRepository = tenantDatabaseRepository;
        this.writer = new AuditLogWriter(platformDatabase.jdbc(), objectMapper);
    }

    public void record(AuditEvent event) {
        try {
            Optional<TenantDatabase> schema = clubSchema(event.clubId());
            if (schema.isEmpty()) {
                log.debug("Club {} has no schema yet; audit event {} not recorded", event.clubId(), event.action());
                return;
            }
            writer.write(schema.get().getDatabaseIdentifier(),
                    TenantSchemaLayout.CLUB_SCOPED.version().equals(schema.get().getSchemaVersion()), event);
        } catch (RuntimeException ex) {
            log.error("Could not record audit event {} for club {}", event.action(), event.clubId(), ex);
        }
    }

    /** The club's schema (the same for all its products). */
    public Optional<TenantDatabase> clubSchema(UUID clubId) {
        return tenantDatabaseRepository.findByClubId(clubId).stream().findFirst();
    }
}
