-- Multi-channel sends: provider set-up must accept every channel the notification tables do
-- (EMAIL, SMS, PUSH, WHATSAPP), and WhatsApp gets a Twilio provider type. The channel checks are
-- only widened; no existing row changes.

ALTER TABLE provider_types DROP CONSTRAINT IF EXISTS provider_types_channel_check;
ALTER TABLE provider_types ADD CONSTRAINT provider_types_channel_check
    CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'WHATSAPP'));

ALTER TABLE club_product_providers DROP CONSTRAINT IF EXISTS club_product_providers_channel_check;
ALTER TABLE club_product_providers ADD CONSTRAINT club_product_providers_channel_check
    CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'WHATSAPP'));

ALTER TABLE usage_limits DROP CONSTRAINT IF EXISTS usage_limits_channel_check;
ALTER TABLE usage_limits ADD CONSTRAINT usage_limits_channel_check
    CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'WHATSAPP'));

-- Same Twilio account as SMS; config {"accountSid", "fromNumber"} with the auth token as the secret.
INSERT INTO provider_types (code, channel, name) VALUES ('TWILIO_WHATSAPP', 'WHATSAPP', 'Twilio WhatsApp')
ON CONFLICT (code) DO NOTHING;
