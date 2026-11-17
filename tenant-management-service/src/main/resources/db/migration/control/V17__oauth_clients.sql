-- OAuth applications (clients) that authenticate with a signed JWT (RFC 7523 private_key_jwt):
-- MiComm stores only each application's PUBLIC key, so nothing stored here lets anyone act as it.
-- Additive only: no existing table or row is changed.

CREATE TABLE IF NOT EXISTS oauth_clients (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    club_id        UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    -- Public identifier the application puts in its assertions (iss / sub), e.g. mcc_k7m2xq9rtw4p8hzd.
    client_id      VARCHAR(40) NOT NULL,
    name           VARCHAR(100) NOT NULL,
    -- X.509 SubjectPublicKeyInfo PEM ("-----BEGIN PUBLIC KEY-----"); RSA 2048+ or EC P-256.
    public_key_pem TEXT NOT NULL,
    -- The only signing algorithm accepted for this client (RS256 or ES256), fixed at registration.
    key_algorithm  VARCHAR(10) NOT NULL CONSTRAINT chk_oauth_clients_alg CHECK (key_algorithm IN ('RS256', 'ES256')),
    -- Space-separated scopes the client may be given, e.g. "email:send email:read".
    scopes         VARCHAR(500) NOT NULL,
    created_by     UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ,
    last_used_at   TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    CONSTRAINT uq_oauth_clients_client_id UNIQUE (client_id)
);

CREATE INDEX IF NOT EXISTS idx_oauth_clients_club ON oauth_clients (club_id, created_at DESC);

-- Assertions already exchanged for a token, so a captured assertion cannot be used twice.
-- Rows are only needed until the assertion would have expired anyway.
CREATE TABLE IF NOT EXISTS oauth_used_assertions (
    client_id  VARCHAR(40) NOT NULL,
    jti        VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_id, jti)
);

CREATE INDEX IF NOT EXISTS idx_oauth_used_assertions_expires ON oauth_used_assertions (expires_at);
