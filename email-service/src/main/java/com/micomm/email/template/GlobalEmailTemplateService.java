package com.micomm.email.template;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.email.audit.EmailAuditLog;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.TenantDatabaseDto;

/**
 * Global email templates: created per club, outside any module, and available in all of the
 * club's products. productId only locates the club's schema (one schema per club); it does not
 * scope the templates. Clubs whose schema has no global_templates table get a 400.
 */
@Service
public class GlobalEmailTemplateService {

    private final GlobalEmailTemplateRepository repository;
    private final TenantManagementClient tenantManagementClient;
    private final ClubAccessGuard clubAccessGuard;
    private final EmailAuditLog auditLog;

    public GlobalEmailTemplateService(GlobalEmailTemplateRepository repository,
            TenantManagementClient tenantManagementClient, ClubAccessGuard clubAccessGuard, EmailAuditLog auditLog) {
        this.repository = repository;
        this.tenantManagementClient = tenantManagementClient;
        this.clubAccessGuard = clubAccessGuard;
        this.auditLog = auditLog;
    }

    public List<EmailTemplateDto> list(UUID clubId, UUID productId) {
        return repository.findAll(resolveTenant(clubId, productId), clubId);
    }

    public EmailTemplateDto get(UUID clubId, UUID productId, UUID templateId) {
        return find(resolveTenant(clubId, productId), clubId, templateId);
    }

    public EmailTemplateDto create(GlobalEmailTemplateCreateRequest request) {
        TenantDatabaseDto tenant = resolveTenant(request.clubId(), request.productId());
        UUID id = repository.create(tenant, request.clubId(), request.name(), request.subject(), request.body());
        // Global templates belong to no product: logged club-wide, located through the request's product.
        auditLog.record(request.productId(), AuditEvent.of(request.clubId(), null,
                AuditActions.GLOBAL_TEMPLATE_CREATED, "GLOBAL_TEMPLATE", id).with("name", request.name()));
        return find(tenant, request.clubId(), id);
    }

    public EmailTemplateDto update(UUID clubId, UUID productId, UUID templateId, EmailTemplateUpdateRequest request) {
        TenantDatabaseDto tenant = resolveTenant(clubId, productId);
        if (!repository.update(tenant, clubId, templateId, request.name(), request.subject(), request.body())) {
            throw new EmailTemplateNotFoundException(templateId);
        }
        auditLog.record(productId, AuditEvent.of(clubId, null, AuditActions.GLOBAL_TEMPLATE_UPDATED,
                "GLOBAL_TEMPLATE", templateId).with("name", request.name()));
        return find(tenant, clubId, templateId);
    }

    public void delete(UUID clubId, UUID productId, UUID templateId) {
        if (!repository.delete(resolveTenant(clubId, productId), clubId, templateId)) {
            throw new EmailTemplateNotFoundException(templateId);
        }
        auditLog.record(productId, AuditEvent.of(clubId, null, AuditActions.GLOBAL_TEMPLATE_DELETED,
                "GLOBAL_TEMPLATE", templateId));
    }

    private EmailTemplateDto find(TenantDatabaseDto tenant, UUID clubId, UUID templateId) {
        return repository.findById(tenant, clubId, templateId)
                .orElseThrow(() -> new EmailTemplateNotFoundException(templateId));
    }

    /** Authorizes the club, then resolves its schema and checks it has global templates. */
    private TenantDatabaseDto resolveTenant(UUID clubId, UUID productId) {
        clubAccessGuard.requireClubAccess(clubId);
        TenantDatabaseDto tenant = tenantManagementClient.resolveTenantDatabase(clubId, productId, null);
        if (!repository.supported(tenant)) {
            throw new IllegalArgumentException(
                    "This club's schema predates global templates; they are available for clubs created since");
        }
        return tenant;
    }
}
