ALTER TABLE tenant_databases ADD COLUMN IF NOT EXISTS product_id UUID REFERENCES products (id);

-- Drop old single-per-club unique constraint (each club now gets one row per product instead)
ALTER TABLE tenant_databases DROP CONSTRAINT IF EXISTS uq_tenant_databases_org;

-- Ensure one schema per (club, product)
ALTER TABLE tenant_databases ADD CONSTRAINT uq_tenant_databases_club_product UNIQUE (club_id, product_id);