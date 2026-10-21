package com.micomm.tenantmgmt.module;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.micomm.tenantmgmt.module.ModuleDtos.TemplateVariable;

/**
 * The template types modules can give default content for. module_template_defaults.template_type
 * and template_key on club-schema templates hold {@link #name()}, so a constant must never be
 * renamed or removed once used; add new types here.
 */
public enum TemplateType {

    WELCOME("Welcome new member", "Sent when a member joins",
            required("memberName"), required("clubName")),
    RENEWAL_REMINDER("Renewal reminder", "Sent before a membership expires",
            required("memberName"), required("clubName"), required("expiryDate")),
    REGISTRATION_CONFIRMATION("Registration confirmation", "Sent when a member registers for an event",
            required("memberName"), required("eventName"), required("eventDate")),
    EVENT_REMINDER("Event reminder", "Sent shortly before an event",
            required("memberName"), required("eventName"), required("eventDate"));

    private final String displayName;
    private final String description;
    private final List<TemplateVariable> variables;

    TemplateType(String displayName, String description, TemplateVariable... variables) {
        this.displayName = displayName;
        this.description = description;
        this.variables = List.of(variables);
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public List<TemplateVariable> variables() {
        return variables;
    }

    public static Optional<TemplateType> fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.name().equals(code)).findFirst();
    }

    private static TemplateVariable required(String name) {
        return new TemplateVariable(name, true);
    }
}
