-- Template types are now defined in code (com.micomm.tenantmgmt.module.TemplateType) instead of
-- the template_types table. module_template_defaults.template_type keeps the same codes; the
-- application checks each code is a known type, the database only checks its format.

ALTER TABLE module_template_defaults
    DROP CONSTRAINT IF EXISTS module_template_defaults_template_type_fkey,
    ADD CONSTRAINT chk_module_template_defaults_type CHECK (template_type ~ '^[A-Z][A-Z0-9_]*$');

DROP TABLE template_types;
