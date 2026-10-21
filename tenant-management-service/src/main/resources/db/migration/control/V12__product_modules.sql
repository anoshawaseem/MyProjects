-- Modules within a product (MiClub -> MEMBERSHIP, EVENT, ...). Templates and sends in
-- club schemas carry the module's code, so module screens only ever see their own templates.
-- Additive only: no existing table or row is changed.

CREATE TABLE IF NOT EXISTS product_modules (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID NOT NULL REFERENCES products (id),
    -- Stored on rows in club schemas, so it never changes once created; rename via name only.
    code        VARCHAR(50) NOT NULL CONSTRAINT chk_product_modules_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                CONSTRAINT chk_product_modules_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_modules_code UNIQUE (product_id, code)
);

-- Which modules a club has switched on for one of its products.
CREATE TABLE IF NOT EXISTS club_product_modules (
    club_product_id UUID NOT NULL REFERENCES club_products (id) ON DELETE CASCADE,
    module_id       UUID NOT NULL REFERENCES product_modules (id),
    enabled         BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_product_id, module_id)
);

-- The templates a module needs (e.g. MEMBERSHIP / RENEWAL_REMINDER) and their placeholders.
CREATE TABLE IF NOT EXISTS module_template_types (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module_id   UUID NOT NULL REFERENCES product_modules (id),
    key         VARCHAR(100) NOT NULL CONSTRAINT chk_module_template_types_key CHECK (key ~ '^[A-Z][A-Z0-9_]*$'),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    -- [{"name": "memberName", "required": true}, ...]
    variables   JSONB NOT NULL DEFAULT '[]'::jsonb,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                CONSTRAINT chk_module_template_types_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_module_template_types_key UNIQUE (module_id, key)
);

-- Product-wide starting content per channel, copied into a club's schema when the club
-- enables the module. version lets a club's copy be compared with / reset to the default.
CREATE TABLE IF NOT EXISTS module_template_defaults (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_type_id UUID NOT NULL REFERENCES module_template_types (id) ON DELETE CASCADE,
    channel          VARCHAR(20) NOT NULL
                     CONSTRAINT chk_module_template_defaults_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'WHATSAPP')),
    subject          VARCHAR(500),
    body             TEXT NOT NULL,
    version          INTEGER NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_module_template_defaults_channel UNIQUE (template_type_id, channel),
    CONSTRAINT chk_module_template_defaults_email_subject CHECK (channel <> 'EMAIL' OR subject IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_module_template_types_module ON module_template_types (module_id);

-- Starting catalogue for MiClub (only if the product exists in this environment).
INSERT INTO product_modules (product_id, code, name, description, sort_order)
SELECT p.id, m.code, m.name, m.description, m.sort_order
FROM products p
CROSS JOIN (VALUES
    ('MEMBERSHIP', 'Membership', 'Member joining, renewals and membership status', 1),
    ('EVENT', 'Events', 'Event registrations and reminders', 2)
) AS m (code, name, description, sort_order)
WHERE p.code = 'miclub'
ON CONFLICT (product_id, code) DO NOTHING;

INSERT INTO module_template_types (module_id, key, name, description, variables)
SELECT pm.id, t.key, t.name, t.description, t.variables::jsonb
FROM product_modules pm
JOIN products p ON p.id = pm.product_id AND p.code = 'miclub'
JOIN (VALUES
    ('MEMBERSHIP', 'WELCOME', 'Welcome new member', 'Sent when a member joins',
     '[{"name":"memberName","required":true},{"name":"clubName","required":true}]'),
    ('MEMBERSHIP', 'RENEWAL_REMINDER', 'Renewal reminder', 'Sent before a membership expires',
     '[{"name":"memberName","required":true},{"name":"clubName","required":true},{"name":"expiryDate","required":true}]'),
    ('EVENT', 'REGISTRATION_CONFIRMATION', 'Registration confirmation', 'Sent when a member registers for an event',
     '[{"name":"memberName","required":true},{"name":"eventName","required":true},{"name":"eventDate","required":true}]'),
    ('EVENT', 'EVENT_REMINDER', 'Event reminder', 'Sent shortly before an event',
     '[{"name":"memberName","required":true},{"name":"eventName","required":true},{"name":"eventDate","required":true}]')
) AS t (module_code, key, name, description, variables) ON t.module_code = pm.code
ON CONFLICT (module_id, key) DO NOTHING;

INSERT INTO module_template_defaults (template_type_id, channel, subject, body)
SELECT tt.id, 'EMAIL', d.subject, d.body
FROM module_template_types tt
JOIN product_modules pm ON pm.id = tt.module_id
JOIN products p ON p.id = pm.product_id AND p.code = 'miclub'
JOIN (VALUES
    ('MEMBERSHIP', 'WELCOME', 'Welcome to {{clubName}}',
     '<p>Hi {{memberName}},</p><p>Welcome to {{clubName}}. We are glad to have you as a member.</p>'),
    ('MEMBERSHIP', 'RENEWAL_REMINDER', 'Your {{clubName}} membership expires on {{expiryDate}}',
     '<p>Hi {{memberName}},</p><p>Your membership with {{clubName}} expires on {{expiryDate}}. Please renew to keep your benefits.</p>'),
    ('EVENT', 'REGISTRATION_CONFIRMATION', 'You are registered for {{eventName}}',
     '<p>Hi {{memberName}},</p><p>You are registered for {{eventName}} on {{eventDate}}.</p>'),
    ('EVENT', 'EVENT_REMINDER', 'Reminder: {{eventName}} on {{eventDate}}',
     '<p>Hi {{memberName}},</p><p>This is a reminder that {{eventName}} takes place on {{eventDate}}.</p>')
) AS d (module_code, key, subject, body) ON d.module_code = pm.code AND d.key = tt.key
ON CONFLICT (template_type_id, channel) DO NOTHING;
