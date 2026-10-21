package com.micomm.email.client.dto;

import java.util.UUID;

/**
 * The club an active API key acts for, as reported by tenant-management-service. name and
 * keyPrefix identify the key in audit logs (null if tenant-management predates them).
 */
public record VerifiedApiKeyDto(UUID apiKeyId, UUID clubId, String name, String keyPrefix) {}
