package com.micomm.tenantmgmt.provider;

import com.micomm.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/provider-types")
public class ProviderTypeController {

    private final ProviderTypeRepository providerTypeRepository;

    public ProviderTypeController(ProviderTypeRepository providerTypeRepository) {
        this.providerTypeRepository = providerTypeRepository;
    }

    @GetMapping
    public ApiResponse<List<ProviderType>> list() {
        return ApiResponse.ok(providerTypeRepository.findAll());
    }
}