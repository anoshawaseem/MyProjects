-- ============================================================
-- MiComm Tenant Database Schema
-- Applied identically to every tenant DB, e.g. micomm_tenant_harbour
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS sms;
CREATE SCHEMA IF NOT EXISTS email;
CREATE SCHEMA IF NOT EXISTS campaign;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS usage;

-- ============================================================
-- SMS
-- ============================================================

CREATE TABLE sms.sms_messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID NOT NULL,           -- reference to control DB products.id (app-validated)
    provider_account_id UUID NOT NULL,           -- reference to control DB provider_accounts.id
    sender              VARCHAR(20),
    message             TEXT NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
                        CHECK (status IN ('REQUESTED', 'QUEUED', 'PROCESSING', 'SENT', 'FAILED')),
    idempotency_key     VARCHAR(100),
    recipient_count     INTEGER NOT NULL DEFAULT 0,
    retry_count         INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    queued_at           TIMESTAMPTZ,
    sent_at             TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ
);

CREATE INDEX idx_sms_messages_product_created ON sms.sms_messages (product_id, created_at);
CREATE INDEX idx_sms_messages_status ON sms.sms_messages (status);
CREATE INDEX idx_sms_messages_provider_created ON sms.sms_messages (provider_account_id, created_at);
CREATE UNIQUE INDEX uq_sms_messages_idempotency
    ON sms.sms_messages (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE sms.sms_recipients (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sms_message_id      UUID NOT NULL REFERENCES sms.sms_messages (id) ON DELETE CASCADE,
    phone_number        VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'BOUNCED', 'FAILED')),
    provider_message_id VARCHAR(100),
    error_code          VARCHAR(50),
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at             TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ
);

CREATE INDEX idx_sms_recipients_message ON sms.sms_recipients (sms_message_id);
CREATE INDEX idx_sms_recipients_status ON sms.sms_recipients (status);
CREATE INDEX idx_sms_recipients_provider_msg ON sms.sms_recipients (provider_message_id);

CREATE TABLE sms.sms_delivery_attempts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sms_recipient_id        UUID NOT NULL REFERENCES sms.sms_recipients (id) ON DELETE CASCADE,
    attempt_number          INTEGER NOT NULL,
    provider_account_id     UUID NOT NULL,
    status                  VARCHAR(20) NOT NULL,
    provider_response_code  VARCHAR(20),
    provider_message_id     VARCHAR(100),
    error_code              VARCHAR(50),
    error_message           TEXT,
    attempted_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sms_delivery_attempts_recipient ON sms.sms_delivery_attempts (sms_recipient_id, attempt_number);
CREATE INDEX idx_sms_delivery_attempts_provider_msg ON sms.sms_delivery_attempts (provider_message_id);

-- ============================================================
-- EMAIL
-- ============================================================

CREATE TABLE email.email_messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID NOT NULL,           -- reference to control DB products.id (app-validated)
    provider_account_id UUID NOT NULL,           -- reference to control DB provider_accounts.id
    template_id         UUID,
    from_email          VARCHAR(255) NOT NULL,
    reply_to            VARCHAR(255),
    subject             VARCHAR(500) NOT NULL,
    html_body           TEXT,
    text_body           TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
                        CHECK (status IN ('REQUESTED', 'QUEUED', 'PROCESSING', 'SENT', 'FAILED')),
    idempotency_key     VARCHAR(100),
    recipient_count     INTEGER NOT NULL DEFAULT 0,
    retry_count         INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    queued_at           TIMESTAMPTZ,
    sent_at             TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ
);

CREATE INDEX idx_email_messages_product_created ON email.email_messages (product_id, created_at);
CREATE INDEX idx_email_messages_status ON email.email_messages (status);
CREATE INDEX idx_email_messages_provider_created ON email.email_messages (provider_account_id, created_at);
CREATE UNIQUE INDEX uq_email_messages_idempotency
    ON email.email_messages (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE email.email_recipients (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_message_id    UUID NOT NULL REFERENCES email.email_messages (id) ON DELETE CASCADE,
    email_address       VARCHAR(255) NOT NULL,
    recipient_type       VARCHAR(10) NOT NULL DEFAULT 'TO'
                        CHECK (recipient_type IN ('TO', 'CC', 'BCC')),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'BOUNCED', 'FAILED')),
    provider_message_id VARCHAR(100),
    error_code          VARCHAR(50),
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at             TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ
);

CREATE INDEX idx_email_recipients_message ON email.email_recipients (email_message_id);
CREATE INDEX idx_email_recipients_status ON email.email_recipients (status);
CREATE INDEX idx_email_recipients_provider_msg ON email.email_recipients (provider_message_id);

CREATE TABLE email.email_delivery_attempts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_recipient_id      UUID NOT NULL REFERENCES email.email_recipients (id) ON DELETE CASCADE,
    attempt_number          INTEGER NOT NULL,
    provider_account_id     UUID NOT NULL,
    status                  VARCHAR(20) NOT NULL,
    provider_response_code  VARCHAR(20),
    provider_message_id     VARCHAR(100),
    error_code              VARCHAR(50),
    error_message           TEXT,
    attempted_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_delivery_attempts_recipient ON email.email_delivery_attempts (email_recipient_id, attempt_number);
CREATE INDEX idx_email_delivery_attempts_provider_msg ON email.email_delivery_attempts (provider_message_id);

-- ============================================================
-- USAGE (per-tenant aggregate, rolled up centrally later)
-- ============================================================

CREATE TABLE usage.notification_usage (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID NOT NULL,
    channel             VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    provider_account_id UUID NOT NULL,
    usage_period        DATE NOT NULL,
    message_count       BIGINT NOT NULL DEFAULT 0,
    recipient_count     BIGINT NOT NULL DEFAULT 0,
    success_count       BIGINT NOT NULL DEFAULT 0,
    failure_count       BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_usage_dimensions UNIQUE (product_id, channel, provider_account_id, usage_period)
);

CREATE INDEX idx_usage_period ON usage.notification_usage (usage_period);

-- ============================================================
-- AUDIT
-- ============================================================

CREATE TABLE audit.audit_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type          TEXT NOT NULL,
    entity_type         TEXT NOT NULL,
    entity_id           UUID,
    actor               TEXT,
    payload_json        JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_entity ON audit.audit_events (entity_type, entity_id);
CREATE INDEX idx_audit_events_created ON audit.audit_events (created_at);

-- ============================================================
-- CAMPAIGN (Phase 2 placeholder — structure only, not used in Phase 1)
-- ============================================================

CREATE TABLE campaign.campaigns (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID NOT NULL,
    name                TEXT NOT NULL,
    channel             VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    scheduled_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- campaign_messages links a campaign to the actual sent messages (reuses sms/email tables)
CREATE TABLE campaign.campaign_messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id         UUID NOT NULL REFERENCES campaign.campaigns (id) ON DELETE CASCADE,
    channel             VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    email_message_id    UUID REFERENCES email.email_messages (id),
    sms_message_id       UUID REFERENCES sms.sms_messages (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_campaign_message_channel CHECK (
        (channel = 'EMAIL' AND email_message_id IS NOT NULL AND sms_message_id IS NULL) OR
        (channel = 'SMS' AND sms_message_id IS NOT NULL AND email_message_id IS NULL)
    )
);

CREATE TABLE campaign.campaign_recipients (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id         UUID NOT NULL REFERENCES campaign.campaigns (id) ON DELETE CASCADE,
    email_address       VARCHAR(255),
    phone_number        VARCHAR(20),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_campaign_recipients_campaign ON campaign.campaign_recipients (campaign_id);