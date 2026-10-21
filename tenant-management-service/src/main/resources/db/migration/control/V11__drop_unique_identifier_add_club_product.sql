-- Remove the old unique constraint that assumed one schema per (club,product).
-- Schemas are now shared across a club's products, so this must no longer be unique.
ALTER TABLE tenant_databases
    DROP CONSTRAINT IF EXISTS uq_tenant_databases_identifier;

-- Add the correct uniqueness guarantee: one row per (club_id, product_id),
-- only if it doesn't already exist (e.g. created by an earlier migration).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_tenant_databases_club_product'
    ) THEN
        ALTER TABLE tenant_databases
            ADD CONSTRAINT uq_tenant_databases_club_product UNIQUE (club_id, product_id);
    END IF;
END $$;

-- Helpful for dropSchemaForProduct's findAllByDatabaseIdentifier lookup
CREATE INDEX IF NOT EXISTS idx_tenant_databases_identifier
    ON tenant_databases (database_identifier);