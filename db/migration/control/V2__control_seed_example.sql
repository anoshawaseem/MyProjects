-- ============================================================
-- Example seed data (matches the worked example: Harbour Club / TopYacht / Northstar)
-- Safe to remove/skip in real environments; useful for local dev.
-- ============================================================

INSERT INTO organizations (id, name, slug, status) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Harbour Club', 'harbour', 'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222', 'Royal Freshwater', 'royal', 'ACTIVE');

INSERT INTO products (id, code, name) VALUES
    ('a1111111-0000-0000-0000-000000000001', 'TOPYACHT', 'TopYacht'),
    ('a1111111-0000-0000-0000-000000000002', 'NORTHSTAR', 'Northstar');

INSERT INTO organization_products (organization_id, product_id, status) VALUES
    ('11111111-1111-1111-1111-111111111111', 'a1111111-0000-0000-0000-000000000001', 'ACTIVE'),
    ('11111111-1111-1111-1111-111111111111', 'a1111111-0000-0000-0000-000000000002', 'ACTIVE');

INSERT INTO provider_types (id, code, channel, name) VALUES
    ('b1111111-0000-0000-0000-000000000001', 'SENDGRID', 'EMAIL', 'SendGrid');

INSERT INTO provider_accounts (id, provider_type_id, name, secret_reference) VALUES
    ('c1111111-0000-0000-0000-000000000001',
     'b1111111-0000-0000-0000-000000000001',
     'SendGrid Account A',
     'arn:aws:secretsmanager:ap-southeast-2:000000000000:secret:sendgrid-account-a');

INSERT INTO organization_product_providers
    (organization_id, product_id, provider_account_id, channel, is_default) VALUES
    ('11111111-1111-1111-1111-111111111111', 'a1111111-0000-0000-0000-000000000001',
     'c1111111-0000-0000-0000-000000000001', 'EMAIL', true),
    ('11111111-1111-1111-1111-111111111111', 'a1111111-0000-0000-0000-000000000002',
     'c1111111-0000-0000-0000-000000000001', 'EMAIL', true);

INSERT INTO tenant_databases
    (organization_id, database_identifier, database_host_reference, database_secret_reference, status) VALUES
    ('11111111-1111-1111-1111-111111111111', 'micomm_tenant_harbour',
     'shared-cluster-1', 'arn:aws:secretsmanager:ap-southeast-2:000000000000:secret:tenant-harbour-db', 'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222', 'micomm_tenant_royal_freshwater',
     'shared-cluster-1', 'arn:aws:secretsmanager:ap-southeast-2:000000000000:secret:tenant-royal-db', 'ACTIVE');