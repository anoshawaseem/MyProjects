-- Provider accounts now belong to a club and can be reused across that club's products
ALTER TABLE provider_accounts ADD COLUMN IF NOT EXISTS club_id UUID REFERENCES clubs (id);

CREATE INDEX IF NOT EXISTS idx_provider_accounts_club ON provider_accounts (club_id);

-- Seed fixed provider types
INSERT INTO provider_types (code, channel, name) VALUES
    ('SENDGRID', 'EMAIL', 'SendGrid'),
    ('SMTP', 'EMAIL', 'SMTP'),
    ('TWILIO', 'SMS', 'Twilio')
ON CONFLICT (code) DO NOTHING;