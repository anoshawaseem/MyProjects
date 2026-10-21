package com.micomm.email.template;

import com.micomm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * {@code module} (e.g. MEMBERSHIP) limits every call to that product module's
 * templates; module screens always pass it.
 */
@RestController
@RequestMapping("/api/v1/emails/templates")
public class EmailTemplateController {

    private final EmailTemplateService service;

    public EmailTemplateController(EmailTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<EmailTemplateDto>> list(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @RequestParam(required = false) String module) {
        return ApiResponse.ok(service.list(clubId, productId, module));
    }

    @GetMapping("/{templateId}")
    public ApiResponse<EmailTemplateDto> get(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @RequestParam(required = false) String module,
            @PathVariable UUID templateId) {
        return ApiResponse.ok(service.get(clubId, productId, module, templateId));
    }

    @PostMapping
    public ApiResponse<EmailTemplateDto> create(@Valid @RequestBody EmailTemplateCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<EmailTemplateDto> update(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @RequestParam(required = false) String module,
            @PathVariable UUID templateId,
            @Valid @RequestBody EmailTemplateUpdateRequest request) {
        return ApiResponse.ok(service.update(clubId, productId, module, templateId, request));
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> delete(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @RequestParam(required = false) String module,
            @PathVariable UUID templateId) {
        service.delete(clubId, productId, module, templateId);
        return ApiResponse.ok(null);
    }
}
