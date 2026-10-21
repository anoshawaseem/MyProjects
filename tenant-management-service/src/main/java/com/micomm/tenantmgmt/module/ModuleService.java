package com.micomm.tenantmgmt.module;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.micomm.tenantmgmt.module.ModuleDtos.AddModuleTemplateRequest;
import com.micomm.tenantmgmt.module.ModuleDtos.ClubModulesDto;
import com.micomm.tenantmgmt.module.ModuleDtos.CreateModuleRequest;
import com.micomm.tenantmgmt.module.ModuleDtos.ModuleTemplateTypeDto;
import com.micomm.tenantmgmt.module.ModuleDtos.ProductModuleDto;
import com.micomm.tenantmgmt.module.ModuleDtos.TemplateDefault;
import com.micomm.tenantmgmt.module.ModuleDtos.TemplateTypeDto;
import com.micomm.tenantmgmt.onboarding.TenantDatabase;
import com.micomm.tenantmgmt.onboarding.TenantDatabaseRepository;
import com.micomm.tenantmgmt.onboarding.TenantOnboardingService;
import com.micomm.tenantmgmt.onboarding.TenantSchemaLayout;
import com.micomm.tenantmgmt.product.ClubProduct;
import com.micomm.tenantmgmt.product.ClubProductRepository;
import com.micomm.tenantmgmt.product.ProductRepository;

/**
 * Product modules (MEMBERSHIP, EVENT, ...) and which of them each club has enabled.
 *
 * Only club schemas on the club-scoped layout ("3") have module-aware tables, so
 * modules can only be enabled for those; older clubs are left exactly as they are.
 */
@Service
public class ModuleService {

    private final ModuleRepository moduleRepository;
    private final ProductRepository productRepository;
    private final ClubProductRepository clubProductRepository;
    private final TenantDatabaseRepository tenantDatabaseRepository;
    private final TenantOnboardingService tenantOnboardingService;

    public ModuleService(ModuleRepository moduleRepository, ProductRepository productRepository,
            ClubProductRepository clubProductRepository, TenantDatabaseRepository tenantDatabaseRepository,
            TenantOnboardingService tenantOnboardingService) {
        this.moduleRepository = moduleRepository;
        this.productRepository = productRepository;
        this.clubProductRepository = clubProductRepository;
        this.tenantDatabaseRepository = tenantDatabaseRepository;
        this.tenantOnboardingService = tenantOnboardingService;
    }

    public List<ProductModuleDto> listModules(UUID productId) {
        requireProduct(productId);
        return moduleRepository.findModules(productId);
    }

    @Transactional
    public ProductModuleDto createModule(UUID productId, CreateModuleRequest request) {
        requireProduct(productId);
        if (moduleRepository.findModule(productId, request.code()).isPresent()) {
            throw new ModuleValidationException("Module " + request.code() + " already exists for this product");
        }
        return moduleRepository.insertModule(productId, request.code(), request.name(), request.description(),
                request.sortOrder() != null ? request.sortOrder() : 0);
    }

    public List<TemplateTypeDto> listTemplateTypes() {
        return Arrays.stream(TemplateType.values()).map(TemplateTypeDto::of).toList();
    }

    public List<ModuleTemplateTypeDto> listModuleTemplateTypes(UUID productId, String moduleCode) {
        ProductModuleDto module = requireModule(productId, moduleCode);
        return moduleRepository.findModuleTemplateTypes(module.id(), module.code());
    }

    /** Gives a module default content for one of the {@link TemplateType}s. */
    @Transactional
    public ModuleTemplateTypeDto addModuleTemplate(UUID productId, String moduleCode,
            AddModuleTemplateRequest request) {
        ProductModuleDto module = requireModule(productId, moduleCode);
        String type = request.templateType();
        if (TemplateType.fromCode(type).isEmpty()) {
            throw new ModuleValidationException("Template type " + type + " does not exist");
        }
        List<TemplateDefault> defaults = request.defaults();
        for (TemplateDefault d : defaults) {
            if ("EMAIL".equals(d.channel()) && (d.subject() == null || d.subject().isBlank())) {
                throw new ModuleValidationException("An EMAIL default needs a subject");
            }
        }
        if (defaults.stream().map(TemplateDefault::channel).distinct().count() != defaults.size()) {
            throw new ModuleValidationException("At most one default per channel");
        }
        if (moduleRepository.findModuleTemplateTypes(module.id(), module.code()).stream()
                .anyMatch(t -> t.key().equals(type))) {
            throw new ModuleValidationException("Template type " + type + " already has defaults in "
                    + module.code());
        }

        defaults.forEach(d -> moduleRepository.insertTemplateDefault(module.id(), type, d));
        return moduleRepository.findModuleTemplateTypes(module.id(), module.code()).stream()
                .filter(t -> t.key().equals(type)).findFirst().orElseThrow();
    }

    public ClubModulesDto clubModules(UUID clubId, UUID productId) {
        ClubProduct clubProduct = requireClubProduct(clubId, productId);
        boolean supportsModules = tenantDatabaseRepository.findByClubIdAndProductId(clubId, productId)
                .map(db -> TenantSchemaLayout.CLUB_SCOPED.version().equals(db.getSchemaVersion()))
                .orElse(false);
        return new ClubModulesDto(supportsModules, moduleRepository.findClubModules(clubProduct.getId(), productId));
    }

    /**
     * Makes exactly the given modules enabled for the club + product. Newly enabled modules get
     * their default templates copied into the club's schema; disabling a module keeps its
     * templates (they are hidden by the services, and come back if it is re-enabled).
     */
    @Transactional
    public ClubModulesDto setClubModules(UUID clubId, UUID productId, List<String> moduleCodes) {
        ClubProduct clubProduct = requireClubProduct(clubId, productId);
        Set<String> wanted = new LinkedHashSet<>(moduleCodes);

        Map<String, ProductModuleDto> modules = moduleRepository.findModules(productId).stream()
                .collect(Collectors.toMap(ProductModuleDto::code, Function.identity()));
        for (String code : wanted) {
            ProductModuleDto module = modules.get(code);
            if (module == null || !"ACTIVE".equals(module.status())) {
                throw new ModuleValidationException("Module " + code + " is not an active module of this product");
            }
        }

        TenantDatabase tenant = null;
        if (!wanted.isEmpty()) {
            tenant = tenantDatabaseRepository.findByClubIdAndProductId(clubId, productId)
                    .orElseThrow(() -> new ModuleValidationException(
                            "The club has no schema for this product yet; onboard it first"));
            if (!TenantSchemaLayout.CLUB_SCOPED.version().equals(tenant.getSchemaVersion())) {
                throw new ModuleValidationException("This club's schema (layout " + tenant.getSchemaVersion()
                        + ") predates modules; modules are available for clubs created since");
            }
        }

        for (ProductModuleDto module : modules.values()) {
            boolean enable = wanted.contains(module.code());
            boolean newlyEnabled = moduleRepository.setEnabled(clubProduct.getId(), module.id(), enable);
            if (newlyEnabled) {
                // Runs in the tenant schema; a failure throws and rolls the control-DB change back.
                tenantOnboardingService.seedModuleTemplates(tenant.getDatabaseIdentifier(), clubId, productId,
                        module.code(), moduleRepository.findSeeds(module.id()));
            }
        }
        return clubModules(clubId, productId);
    }

    public List<String> enabledModuleCodes(UUID clubId, UUID productId) {
        return moduleRepository.findEnabledModuleCodes(clubId, productId);
    }

    private void requireProduct(UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw new IllegalArgumentException("Product not found: " + productId);
        }
    }

    private ProductModuleDto requireModule(UUID productId, String moduleCode) {
        requireProduct(productId);
        return moduleRepository.findModule(productId, moduleCode)
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleCode));
    }

    private ClubProduct requireClubProduct(UUID clubId, UUID productId) {
        return clubProductRepository.findByClubId(clubId).stream()
                .filter(cp -> cp.getProductId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Product " + productId + " is not enabled for club "
                        + clubId));
    }
}
