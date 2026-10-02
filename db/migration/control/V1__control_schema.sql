-- ============================================================
-- MiComm Control Database Schema
-- Database: micomm_control
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto"; -- for gen_random_uuid()

-- ============================================================
-- organizations
-- ============================================================
CREATE TABLE organizations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,
    slug                TEXT NOT NULL,
    timezone            TEXT NOT NULL DEFAULT 'UTC',
    country             TEXT,
    contact_name        TEXT,
    contact_email       TEXT,
    contact_phone       TEXT,
    status              TEXT NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DECOMMISSIONED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_organizations_slug UNIQUE (slug)
);

CREATE INDEX idx_organizations_status ON organizations (status);

-- ============================================================
-- products
-- ============================================================
CREATE TABLE products (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                TEXT NOT NULL,
    name                TEXT NOT NULL,
    description         TEXT,
    status              TEXT NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_products_code UNIQUE (code)
);

-- ============================================================
-- organization_products
-- ============================================================
CREATE TABLE organization_products (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations (id),
    product_id          UUID NOT NULL REFERENCES products (id),
    status              TEXT NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_org_product UNIQUE (organization_id, product_id)
);

CREATE INDEX idx_org_products_org ON organization_products (organization_id);
CREATE INDEX idx_org_products_product ON organization_products (product_id);

-- ============================================================
-- provider_types
-- ============================================================
CREATE TABLE provider_types (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                TEXT NOT NULL,
    channel             TEXT NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    name                TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_types_code UNIQUE (code)
);

-- ============================================================
-- provider_accounts
-- ============================================================
CREATE TABLE provider_accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_type_id    UUID NOT NULL REFERENCES provider_types (id),
    name                TEXT NOT NULL,
    secret_reference    TEXT NOT NULL, -- AWS Secrets Manager ARN, never raw secret
    config_json         JSONB,
    status              TEXT NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_provider_accounts_type ON provider_accounts (provider_type_id);

-- ============================================================
-- organization_product_providers
-- ============================================================
CREATE TABLE organization_product_providers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations (id),
    product_id          UUID NOT NULL REFERENCES products (id),
    provider_account_id UUID NOT NULL REFERENCES provider_accounts (id),
    channel             TEXT NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    is_default          BOOLEAN NOT NULL DEFAULT true,
    status              TEXT NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_opp_org_product ON organization_product_providers (organization_id, product_id);
CREATE INDEX idx_opp_provider_account ON organization_product_providers (provider_account_id);

-- Only one default provider per org+product+channel
CREATE UNIQUE INDEX uq_opp_default_per_channel
    ON organization_product_providers (organization_id, product_id, channel)
    WHERE is_default = true;

-- ============================================================
-- tenant_databases
-- ============================================================
CREATE TABLE tenant_databases (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL REFERENCES organizations (id),
    database_identifier         TEXT NOT NULL,
    database_host_reference     TEXT NOT NULL,
    database_secret_reference   TEXT NOT NULL, -- Secrets Manager ARN for DB credentials
    isolation_tier              TEXT NOT NULL DEFAULT 'STANDARD'
                                CHECK (isolation_tier IN ('STANDARD', 'DEDICATED', 'ENTERPRISE')),
    status                      TEXT NOT NULL DEFAULT 'PROVISIONING'
                                CHECK (status IN ('PROVISIONING', 'ACTIVE', 'MIGRATING', 'DECOMMISSIONED')),
    schema_version               TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_databases_org UNIQUE (organization_id),
    CONSTRAINT uq_tenant_databases_identifier UNIQUE (database_identifier)
);

CREATE INDEX idx_tenant_databases_status ON tenant_databases (status);

-- ============================================================
-- usage_limits
-- ============================================================
CREATE TABLE usage_limits (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations (id),
    product_id          UUID REFERENCES products (id), -- null = org-wide limit
    channel             TEXT NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    period_type         TEXT NOT NULL CHECK (period_type IN ('DAILY', 'MONTHLY')),
    max_count           BIGINT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_limits_org ON usage_limits (organization_id, product_id, channel);

-- ============================================================
-- api_clients
-- ============================================================
CREATE TABLE api_clients (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations (id),
    product_id          UUID NOT NULL REFERENCES products (id),
    client_key          TEXT NOT NULL,
    client_secret_hash  TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_api_clients_key UNIQUE (client_key)
);

CREATE INDEX idx_api_clients_org_product ON api_clients (organization_id, product_id);