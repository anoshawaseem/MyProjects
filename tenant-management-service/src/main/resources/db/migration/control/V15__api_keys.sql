-- API keys let an application call MiComm for one club without a user's email and password.
-- Only a SHA-256 hash of each key is stored; the key itself is shown once, when it is created.
-- Additive only: no existing table or row is changed.

CREATE TABLE IF NOT EXISTS api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    club_id      UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    -- Public part of the key ("mck_<prefix>_..."), so people can tell keys apart in lists and logs.
    key_prefix   VARCHAR(16) NOT NULL,
    key_hash     CHAR(64) NOT NULL,
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    CONSTRAINT uq_api_keys_hash UNIQUE (key_hash),
    CONSTRAINT uq_api_keys_prefix UNIQUE (key_prefix)
);

CREATE INDEX IF NOT EXISTS idx_api_keys_club ON api_keys (club_id, created_at DESC);
