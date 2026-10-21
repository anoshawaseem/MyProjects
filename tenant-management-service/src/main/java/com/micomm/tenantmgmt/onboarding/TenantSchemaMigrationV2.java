package com.micomm.tenantmgmt.onboarding;

import java.util.List;

/**
 * Migrates existing tenant schemas (created before the email_logs restructure)
 * to the new layout:
 * email_logs(id, subject, body, status, sent_at, created_at) -- no more
 * "recipient" column
 * email_log_recipients(id, email_log_id, recipient, status, error_message,
 * sent_at, created_at)
 *
 * Safe to run multiple times (idempotent) - uses IF NOT EXISTS / IF EXISTS
 * guards.
 */
public final class TenantSchemaMigrationV2 {

    private TenantSchemaMigrationV2() {
    }

    public static List<String> migrationStatements() {
        return List.of(
                // Add "body" column if it doesn't exist yet (old schema only had subject)
                """
                        ALTER TABLE email_logs
                        ADD COLUMN IF NOT EXISTS body TEXT
                        """,

                // Create the new recipients table if it doesn't exist
                """
                        CREATE TABLE IF NOT EXISTS email_log_recipients (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            email_log_id UUID NOT NULL REFERENCES email_logs(id) ON DELETE CASCADE,
                            recipient VARCHAR(320) NOT NULL,
                            status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                            error_message TEXT,
                            sent_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """,

                // Backfill: migrate old per-recipient email_logs rows into
                // email_log_recipients, preserving existing data.
                // Only runs if the old "recipient" column still exists on email_logs.
                """
                        DO $$
                        BEGIN
                            IF EXISTS (
                                SELECT 1 FROM information_schema.columns
                                WHERE table_name = 'email_logs' AND column_name = 'recipient'
                            ) THEN
                                INSERT INTO email_log_recipients (id, email_log_id, recipient, status, sent_at, created_at)
                                SELECT gen_random_uuid(), id, recipient, status, sent_at, created_at
                                FROM email_logs;

                                ALTER TABLE email_logs DROP COLUMN recipient;
                            END IF;
                        END $$;
                        """,

                // Ensure status/sent_at semantics match new "overall status" meaning
                """
                        ALTER TABLE email_logs
                        ALTER COLUMN status SET DEFAULT 'PENDING'
                        """,

                // Indexes for the new recipients table
                """
                        CREATE INDEX IF NOT EXISTS idx_email_log_recipients_log
                        ON email_log_recipients (email_log_id)
                        """,
                """
                        CREATE INDEX IF NOT EXISTS idx_email_log_recipients_status
                        ON email_log_recipients (status)
                        """);
    }
}