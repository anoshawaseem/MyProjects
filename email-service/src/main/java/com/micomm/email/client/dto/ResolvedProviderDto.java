package com.micomm.email.client.dto;

import java.util.UUID;

public record ResolvedProviderDto(
        UUID providerAccountId,
        String providerTypeCode,
        String secretReference,
        String configJson
) {}