-- Template types (WELCOME, RENEWAL_REMINDER, ...) become one product-wide lookup table instead of
-- a per-module table: module_template_types is dropped and module_template_defaults says which
-- module and which type each default belongs to. Control database only; club schemas are untouched.

CREATE TABLE IF NOT EXISTS template_types (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Stored as template_key on rows in club schemas, so it never changes once created.
    code        VARCHAR(100) NOT NULL CONSTRAINT chk_template_types_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    -- [{"name": "memberName", "required": true}, ...]
    variables   JSONB NOT NULL DEFAULT '[]'::jsonb,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                CONSTRAINT chk_template_types_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_template_types_code UNIQUE (code)
);

-- Carry the existing types over, keeping their ids so source_type_id on templates already
-- copied into club schemas still points at the right type.
INSERT INTO template_types (id, code, name, description, variables, status, created_at, updated_at)
SELECT DISTINCT ON (key) id, key, name, description, variables, status, created_at, updated_at
FROM module_template_types
ORDER BY key, created_at
ON CONFLICT (code) DO NOTHING;

ALTER TABLE module_template_defaults
    ADD COLUMN module_id     UUID REFERENCES product_modules (id),
    ADD COLUMN template_type VARCHAR(100) REFERENCES template_types (code);

UPDATE module_template_defaults d
SET module_id = t.module_id, template_type = t.key
FROM module_template_types t
WHERE t.id = d.template_type_id;

-- Dropping template_type_id also drops its FK and the old (template_type_id, channel) unique key.
ALTER TABLE module_template_defaults
    ALTER COLUMN module_id SET NOT NULL,
    ALTER COLUMN template_type SET NOT NULL,
    DROP COLUMN template_type_id,
    ADD CONSTRAINT uq_module_template_defaults_channel UNIQUE (module_id, template_type, channel);

CREATE INDEX IF NOT EXISTS idx_module_template_defaults_module ON module_template_defaults (module_id);

DROP TABLE module_template_types;
