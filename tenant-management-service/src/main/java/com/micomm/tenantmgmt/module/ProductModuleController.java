package com.micomm.tenantmgmt.module;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.MicommAuthenticatedUser;
import com.micomm.tenantmgmt.module.ModuleDtos.AddModuleTemplateRequest;
import com.micomm.tenantmgmt.module.ModuleDtos.CreateModuleRequest;
import com.micomm.tenantmgmt.module.ModuleDtos.ModuleTemplateTypeDto;
import com.micomm.tenantmgmt.module.ModuleDtos.ProductModuleDto;

import jakarta.validation.Valid;

/** The module catalogue of a product: readable by any signed-in user, managed by ORG_ADMIN. */
@RestController
@RequestMapping("/products/{productId}/modules")
public class ProductModuleController {

    private final ModuleService moduleService;

    public ProductModuleController(ModuleService moduleService) {
        this.moduleService = moduleService;
    }

    @GetMapping
    public ApiResponse<List<ProductModuleDto>> list(@PathVariable UUID productId) {
        return ApiResponse.ok(moduleService.listModules(productId));
    }

    @PostMapping
    public ApiResponse<ProductModuleDto> create(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID productId,
            @Valid @RequestBody CreateModuleRequest request) {
        requireOrgAdmin(currentUser);
        return ApiResponse.ok(moduleService.createModule(productId, request));
    }

    @GetMapping("/{moduleCode}/template-types")
    public ApiResponse<List<ModuleTemplateTypeDto>> templateTypes(@PathVariable UUID productId,
            @PathVariable String moduleCode) {
        return ApiResponse.ok(moduleService.listModuleTemplateTypes(productId, moduleCode));
    }

    @PostMapping("/{moduleCode}/template-types")
    public ApiResponse<ModuleTemplateTypeDto> addTemplate(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID productId,
            @PathVariable String moduleCode,
            @Valid @RequestBody AddModuleTemplateRequest request) {
        requireOrgAdmin(currentUser);
        return ApiResponse.ok(moduleService.addModuleTemplate(productId, moduleCode, request));
    }

    static void requireOrgAdmin(MicommAuthenticatedUser currentUser) {
        if (!currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can manage product modules");
        }
    }
}
