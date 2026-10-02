DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'organizations') THEN
        ALTER TABLE organizations RENAME TO clubs;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'organization_id') THEN
        ALTER TABLE users RENAME COLUMN organization_id TO club_id;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'tenant_databases' AND column_name = 'organization_id') THEN
        ALTER TABLE tenant_databases RENAME COLUMN organization_id TO club_id;
    END IF;
END $$;