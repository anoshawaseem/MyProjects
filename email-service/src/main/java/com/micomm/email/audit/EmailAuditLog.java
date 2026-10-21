package com.micomm.email.audit;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.audit.AuditLogWriter;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.TenantDatabaseDto;

/**
 * Records email-service actions in the club's audit log (its schema's audit_logs table). The
 * club's schema is found through any of its products; events with no product (global templates)
 * need the product the request came in on to locate it. Never throws.
 */
@Component
public class EmailAuditLog {

    private static final Logger log = LoggerFactory.getLogger(EmailAuditLog.class);

    private final TenantManagementClient tenantManagementClient;
    private final AuditLogWriter writer;

    public EmailAuditLog(TenantManagementClient tenantManagementClient, JdbcTemplate platformJdbcTemplate,
            ObjectMapper objectMapper) {
        this.tenantManagementClient = tenantManagementClient;
        this.writer = new AuditLogWriter(platformJdbcTemplate, objectMapper);
    }

    /** @param viaProductId the product used to find the club's schema (usually the event's own) */
    public void record(UUID viaProductId, AuditEvent event) {
        try {
            TenantDatabaseDto tenant = tenantManagementClient.resolveTenantDatabase(event.clubId(), viaProductId);
            writer.write(tenant.schemaName(), tenant.clubScoped(), event);
        } catch (RuntimeException ex) {
            log.error("Could not record audit event {} for club {}", event.action(), event.clubId(), ex);
        }
    }

    public void record(AuditEvent event) {
        record(event.productId(), event);
    }
}
