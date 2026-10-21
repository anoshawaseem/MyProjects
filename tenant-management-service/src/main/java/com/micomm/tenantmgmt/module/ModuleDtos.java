package com.micomm.tenantmgmt.module;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request / response shapes for product modules and their template types. */
public final class ModuleDtos {

    private ModuleDtos() {
    }

    /** Module and template-type codes: upper case, stored on rows in club schemas. */
    static final String CODE_PATTERN = "^[A-Z][A-Z0-9_]*$";

    public record ProductModuleDto(UUID id, UUID productId, String code, String name, String description,
            String status, int sortOrder) {
    }

    public record CreateModuleRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = CODE_PATTERN) String code,
            @NotBlank @Size(max = 100) String name,
            String description,
            Integer sortOrder) {
    }

    public record TemplateVariable(@NotBlank String name, boolean required) {
    }

    public record TemplateDefault(
            @NotBlank @Pattern(regexp = "^(EMAIL|SMS|PUSH|WHATSAPP)$") String channel,
            @Size(max = 500) String subject,
            @NotBlank String body,
            Integer version) {
    }

    /** One of the {@link TemplateType} constants (WELCOME, RENEWAL_REMINDER, ...). */
    public record TemplateTypeDto(String code, String name, String description, List<TemplateVariable> variables) {

        static TemplateTypeDto of(TemplateType type) {
            return new TemplateTypeDto(type.name(), type.displayName(), type.description(), type.variables());
        }
    }

    /** A template type as used by one module, with that module's default content per channel. */
    public record ModuleTemplateTypeDto(String moduleCode, String key, String name, String description,
            List<TemplateVariable> variables, List<TemplateDefault> defaults) {
    }

    /** Gives a module default content for an existing template type. */
    public record AddModuleTemplateRequest(
            @NotBlank @Pattern(regexp = CODE_PATTERN) String templateType,
            @NotEmpty List<@Valid TemplateDefault> defaults) {
    }

    /** A module as seen by one club + product: whether the club has it switched on. */
    public record ClubModuleDto(String code, String name, String description, boolean enabled) {
    }

    /** supportsModules is false for clubs whose schema predates modules (they cannot enable any). */
    public record ClubModulesDto(boolean supportsModules, List<ClubModuleDto> modules) {
    }

    public record SetClubModulesRequest(@NotNull List<@Pattern(regexp = CODE_PATTERN) String> moduleCodes) {
    }
}
