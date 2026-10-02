-- ============================================================
-- Example seed data matching the worked example
-- Run against micomm_tenant_harbour only (local dev / demo)
-- ============================================================

-- Case A: TopYacht 1-to-1 transactional email
INSERT INTO email.email_messages
    (id, product_id, provider_account_id, from_email, subject, status, recipient_count, idempotency_key, created_at, sent_at)
VALUES
    ('e0000000-0000-0000-0000-000000001001',
     'a1111111-0000-0000-0000-000000000001', -- prod-topyacht
     'c1111111-0000-0000-0000-000000000001', -- pa-sg-a
     'booking@topyacht.com', 'Your booking is confirmed', 'SENT', 1,
     'topyacht-booking-88213', '2026-09-24 09:00:00+00', '2026-09-24 09:00:03+00');

INSERT INTO email.email_recipients
    (id, email_message_id, email_address, recipient_type, status, provider_message_id, sent_at, delivered_at)
VALUES
    ('e0000000-0000-0000-0000-000000002001',
     'e0000000-0000-0000-0000-000000001001',
     'jane.smith@example.com', 'TO', 'DELIVERED', 'sg-msg-abc123',
     '2026-09-24 09:00:03+00', '2026-09-24 09:00:07+00');

INSERT INTO email.email_delivery_attempts
    (email_recipient_id, attempt_number, provider_account_id, status, provider_response_code, attempted_at)
VALUES
    ('e0000000-0000-0000-0000-000000002001', 1, 'c1111111-0000-0000-0000-000000000001', 'SENT', '202', '2026-09-24 09:00:03+00'),
    ('e0000000-0000-0000-0000-000000002001', 1, 'c1111111-0000-0000-0000-000000000001', 'DELIVERED', '250', '2026-09-24 09:00:07+00');

-- Case B: Northstar bulk email to 3 recipients
INSERT INTO email.email_messages
    (id, product_id, provider_account_id, from_email, subject, status, recipient_count, idempotency_key)
VALUES
    ('e0000000-0000-0000-0000-000000001002',
     'a1111111-0000-0000-0000-000000000002', -- prod-northstar
     'c1111111-0000-0000-0000-000000000001', -- pa-sg-a
     'notices@northstar.com', 'Club AGM Notice', 'PROCESSING', 3,
     'northstar-agm-2026-09');

INSERT INTO email.email_recipients
    (id, email_message_id, email_address, recipient_type, status, provider_message_id)
VALUES
    ('e0000000-0000-0000-0000-000000002002', 'e0000000-0000-0000-0000-000000001002', 'member1@example.com', 'TO', 'DELIVERED', 'sg-msg-x1'),
    ('e0000000-0000-0000-0000-000000002003', 'e0000000-0000-0000-0000-000000001002', 'member2@example.com', 'TO', 'BOUNCED',   'sg-msg-x2'),
    ('e0000000-0000-0000-0000-000000002004', 'e0000000-0000-0000-0000-000000001002', 'member3@example.com', 'TO', 'SENT',      'sg-msg-x3');

INSERT INTO email.email_delivery_attempts
    (email_recipient_id, attempt_number, provider_account_id, status, provider_response_code)
VALUES
    ('e0000000-0000-0000-0000-000000002002', 1, 'c1111111-0000-0000-0000-000000000001', 'DELIVERED', '250'),
    ('e0000000-0000-0000-0000-000000002003', 1, 'c1111111-0000-0000-0000-000000000001', 'BOUNCED',   '550'),
    ('e0000000-0000-0000-0000-000000002004', 1, 'c1111111-0000-0000-0000-000000000001', 'SENT',      '202');

-- Usage aggregate rows (would normally be upserted by the application on each terminal status change)
INSERT INTO usage.notification_usage
    (product_id, channel, provider_account_id, usage_period, message_count, recipient_count, success_count, failure_count)
VALUES
    ('a1111111-0000-0000-0000-000000000001', 'EMAIL', 'c1111111-0000-0000-0000-000000000001', '2026-09-24', 1, 1, 1, 0),
    ('a1111111-0000-0000-0000-000000000002', 'EMAIL', 'c1111111-0000-0000-0000-000000000001', '2026-09-24', 1, 3, 2, 1);