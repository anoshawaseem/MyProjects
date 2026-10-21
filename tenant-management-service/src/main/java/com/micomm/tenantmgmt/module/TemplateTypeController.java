package com.micomm.tenantmgmt.module;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.common.dto.ApiResponse;
import com.micomm.tenantmgmt.module.ModuleDtos.TemplateTypeDto;

/** The template types defined in {@link TemplateType}; new types are added in code, not via the API. */
@RestController
@RequestMapping("/template-types")
public class TemplateTypeController {

    private final ModuleService moduleService;

    public TemplateTypeController(ModuleService moduleService) {
        this.moduleService = moduleService;
    }

    @GetMapping
    public ApiResponse<List<TemplateTypeDto>> list() {
        return ApiResponse.ok(moduleService.listTemplateTypes());
    }
}
