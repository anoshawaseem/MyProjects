package com.micomm.email.template;

import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.email.audit.EmailAuditLog;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.tenant.TenantModules;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Email templates. In module-aware (club-scoped) schemas every template belongs
 * to a product module: a module screen passes its module and only ever sees
 * that module's templates; without a module, templates of all the club's
 * enabled modules are visible. Older clubs have no modules: a module filter
 * matches nothing there, and creating with a module is rejected.
 */
@Service
public class EmailTemplateService {

    private final EmailTemplateRepository repository;
    private final GlobalEmailTemplateRepository globalRepository;
    private final TenantManagementClient tenantManagementClient;
    private final ClubAccessGuard clubAccessGuard;
    private final EmailAuditLog auditLog;

    public EmailTemplateService(EmailTemplateRepository repository, GlobalEmailTemplateRepository globalRepository,
            TenantManagementClient tenantManagementClient, ClubAccessGuard clubAccessGuard, EmailAuditLog auditLog) {
        this.repository = repository;
        this.globalRepository = globalRepository;
        this.auditLog = auditLog;
        this.tenantManagementClient = tenantManagementClient;
        this.clubAccessGuard = clubAccessGuard;
    }

    public List<EmailTemplateDto> list(UUID clubId, UUID productId, String module) {
        String moduleCode = TenantModules.normalize(module);
        TenantDatabaseDto tenant = resolveTenant(clubId, productId, moduleCode);
        if (moduleCode != null && !tenant.supportsModules()) {
            return List.of();
        }
        return repository.findAll(tenant, clubId, productId, modules(tenant, moduleCode));
    }

    public EmailTemplateDto get(UUID clubId, UUID productId, String module, UUID templateId) {
        String moduleCode = TenantModules.normalize(module);
        TenantDatabaseDto tenant = resolveTenant(clubId, productId, moduleCode);
        return find(tenant, clubId, productId, moduleCode, templateId);
    }

    public EmailTemplateDto create(EmailTemplateCreateRequest request) {
        String moduleCode = TenantModules.normalize(request.moduleCode());
        TenantDatabaseDto tenant = resolveTenant(request.clubId(), request.productId(), moduleCode);
        if (tenant.supportsModules()) {
            if (moduleCode == null) {
                throw new IllegalArgumentException("'moduleCode' is required: every template belongs to a module");
            }
            TenantModules.requireEnabled(tenant, moduleCode);
        } else if (moduleCode != null || request.templateKey() != null) {
            throw new TenantModules.ModuleNotSupportedException();
        }
        UUID id = repository.create(tenant, request.clubId(), request.productId(), moduleCode,
                request.templateKey(), request.name(), request.subject(), request.body());
        auditLog.record(AuditEvent.of(request.clubId(), request.productId(), AuditActions.TEMPLATE_CREATED,
                "TEMPLATE", id).with("name", request.name()).with("module", moduleCode)
                .with("templateKey", request.templateKey()));
        return find(tenant, request.clubId(), request.productId(), moduleCode, id);
    }

    public EmailTemplateDto update(UUID clubId, UUID productId, String module, UUID templateId,
            EmailTemplateUpdateRequest request) {
        String moduleCode = TenantModules.normalize(module);
        TenantDatabaseDto tenant = resolveTenant(clubId, productId, moduleCode);
        boolean updated = moduleCode != null && !tenant.supportsModules() ? false
                : repository.update(tenant, clubId, productId, modules(tenant, moduleCode), templateId,
                        request.name(), request.subject(), request.body());
        if (!updated) {
            throw new EmailTemplateNotFoundException(templateId);
        }
        auditLog.record(AuditEvent.of(clubId, productId, AuditActions.TEMPLATE_UPDATED, "TEMPLATE", templateId)
                .with("name", request.name()).with("module", moduleCode));
        return find(tenant, clubId, productId, moduleCode, templateId);
    }

    public void delete(UUID clubId, UUID productId, String module, UUID templateId) {
        String moduleCode = TenantModules.normalize(module);
        TenantDatabaseDto tenant = resolveTenant(clubId, productId, moduleCode);
        boolean deleted = moduleCode != null && !tenant.supportsModules() ? false
                : repository.delete(tenant, clubId, productId, modules(tenant, moduleCode), templateId);
        if (!deleted) {
            throw new EmailTemplateNotFoundException(templateId);
        }
        auditLog.record(AuditEvent.of(clubId, productId, AuditActions.TEMPLATE_DELETED, "TEMPLATE", templateId)
                .with("module", moduleCode));
    }

    /**
     * The template a send refers to, by id or by module + template key. An id that is not one of
     * the visible module templates may be one of the club's global templates. The caller has
     * already authorized the club and resolved the tenant.
     */
    public EmailTemplateDto findForSend(TenantDatabaseDto tenant, UUID clubId, UUID productId, String moduleCode,
            UUID templateId, String templateKey) {
        if (templateId != null) {
            try {
                return find(tenant, clubId, productId, moduleCode, templateId);
            } catch (EmailTemplateNotFoundException ex) {
                if (!globalRepository.supported(tenant)) {
                    throw ex;
                }
                return globalRepository.findById(tenant, clubId, templateId).orElseThrow(() -> ex);
            }
        }
        TenantModules.requireEnabled(tenant, moduleCode);
        return repository.findByKey(tenant, clubId, productId, moduleCode, templateKey)
                .orElseThrow(() -> new EmailTemplateNotFoundException(moduleCode + "/" + templateKey));
    }

    private EmailTemplateDto find(TenantDatabaseDto tenant, UUID clubId, UUID productId, String moduleCode,
            UUID templateId) {
        if (moduleCode != null && !tenant.supportsModules()) {
            throw new EmailTemplateNotFoundException(templateId);
        }
        return repository.findById(tenant, clubId, productId, modules(tenant, moduleCode), templateId)
                .orElseThrow(() -> new EmailTemplateNotFoundException(templateId));
    }

    /** Modules visible to the request (club-scoped schemas); older layouts ignore it. */
    private static List<String> modules(TenantDatabaseDto tenant, String moduleCode) {
        return tenant.supportsModules() ? TenantModules.visible(tenant, moduleCode) : List.of();
    }

    /** Every operation goes through here: authorize the club before touching its tenant schema. */
    private TenantDatabaseDto resolveTenant(UUID clubId, UUID productId, String moduleCode) {
        clubAccessGuard.requireClubAccess(clubId);
        return tenantManagementClient.resolveTenantDatabase(clubId, productId, moduleCode);
    }
}
