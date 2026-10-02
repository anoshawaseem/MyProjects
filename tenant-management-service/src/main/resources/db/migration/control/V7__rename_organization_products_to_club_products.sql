DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'organization_products') THEN
        ALTER TABLE organization_products RENAME TO club_products;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'club_products' AND column_name = 'organization_id') THEN
        ALTER TABLE club_products RENAME COLUMN organization_id TO club_id;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'organization_product_providers') THEN
        ALTER TABLE organization_product_providers RENAME TO club_product_providers;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'club_product_providers' AND column_name = 'organization_id') THEN
        ALTER TABLE club_product_providers RENAME COLUMN organization_id TO club_id;
    END IF;
END $$;

-- Rename indexes/constraints for clarity (safe no-ops if already renamed)
ALTER INDEX IF EXISTS organization_products_pkey RENAME TO club_products_pkey;
ALTER INDEX IF EXISTS idx_org_products_org RENAME TO idx_club_products_club;
ALTER INDEX IF EXISTS idx_org_products_product RENAME TO idx_club_products_product;
ALTER INDEX IF EXISTS uq_org_product RENAME TO uq_club_product;

ALTER INDEX IF EXISTS organization_product_providers_pkey RENAME TO club_product_providers_pkey;
ALTER INDEX IF EXISTS idx_opp_org_product RENAME TO idx_cpp_club_product;
ALTER INDEX IF EXISTS idx_opp_provider_account RENAME TO idx_cpp_provider_account;
ALTER INDEX IF EXISTS uq_opp_default_per_channel RENAME TO uq_cpp_default_per_channel;

-- Rename FK/check constraints
ALTER TABLE club_products RENAME CONSTRAINT organization_products_organization_id_fkey TO club_products_club_id_fkey;
ALTER TABLE club_products RENAME CONSTRAINT organization_products_product_id_fkey TO club_products_product_id_fkey;
ALTER TABLE club_products RENAME CONSTRAINT organization_products_status_check TO club_products_status_check;

ALTER TABLE club_product_providers RENAME CONSTRAINT organization_product_providers_organization_id_fkey TO club_product_providers_club_id_fkey;
ALTER TABLE club_product_providers RENAME CONSTRAINT organization_product_providers_product_id_fkey TO club_product_providers_product_id_fkey;
ALTER TABLE club_product_providers RENAME CONSTRAINT organization_product_providers_provider_account_id_fkey TO club_product_providers_provider_account_id_fkey;
ALTER TABLE club_product_providers RENAME CONSTRAINT organization_product_providers_channel_check TO club_product_providers_channel_check;
ALTER TABLE club_product_providers RENAME CONSTRAINT organization_product_providers_status_check TO club_product_providers_status_check;