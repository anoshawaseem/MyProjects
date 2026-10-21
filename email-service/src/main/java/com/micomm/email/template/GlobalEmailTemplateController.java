package com.micomm.email.template;

import com.micomm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The club's global email templates (no module, shared by all of its products).
 * {@code productId} is any of the club's products; it only locates the club's schema.
 */
@RestController
@RequestMapping("/api/v1/emails/global-templates")
public class GlobalEmailTemplateController {

    private final GlobalEmailTemplateService service;

    public GlobalEmailTemplateController(GlobalEmailTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<EmailTemplateDto>> list(@RequestParam UUID clubId, @RequestParam UUID productId) {
        return ApiResponse.ok(service.list(clubId, productId));
    }

    @GetMapping("/{templateId}")
    public ApiResponse<EmailTemplateDto> get(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @PathVariable UUID templateId) {
        return ApiResponse.ok(service.get(clubId, productId, templateId));
    }

    @PostMapping
    public ApiResponse<EmailTemplateDto> create(@Valid @RequestBody GlobalEmailTemplateCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<EmailTemplateDto> update(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @PathVariable UUID templateId,
            @Valid @RequestBody EmailTemplateUpdateRequest request) {
        return ApiResponse.ok(service.update(clubId, productId, templateId, request));
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> delete(
            @RequestParam UUID clubId,
            @RequestParam UUID productId,
            @PathVariable UUID templateId) {
        service.delete(clubId, productId, templateId);
        return ApiResponse.ok(null);
    }
}
