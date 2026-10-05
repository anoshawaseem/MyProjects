package com.micomm.tenantmgmt.provider;

import java.util.UUID;

/**
 * Internal DTO carrying resolved provider config + secret.
 * Only ever returned over the internal service-to-service endpoint —
 * never exposed via public-facing controllers.
 */
public record ResolvedProviderDto(
        UUID providerAccountId,
        String providerTypeCode,   // SMTP | SENDGRID | TWILIO
        String secretReference,    // plaintext for now; swap for Secrets Manager lookup later
        String configJson
) {}