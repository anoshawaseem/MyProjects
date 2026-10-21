-- Optional provider account per module: a club can send, say, Membership email through one
-- SendGrid account and Events email through another. Sends from a module without an entry here
-- (and sends from no module) keep using the product's provider in club_product_providers.
-- Additive only: no existing table or row is changed.

CREATE TABLE IF NOT EXISTS club_module_providers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    club_id             UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    product_id          UUID NOT NULL REFERENCES products (id),
    module_id           UUID NOT NULL REFERENCES product_modules (id) ON DELETE CASCADE,
    channel             VARCHAR(20) NOT NULL
                        CONSTRAINT chk_club_module_providers_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'WHATSAPP')),
    provider_account_id UUID NOT NULL REFERENCES provider_accounts (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_club_module_providers UNIQUE (club_id, product_id, module_id, channel)
);
