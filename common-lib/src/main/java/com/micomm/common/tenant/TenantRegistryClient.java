package com.micomm.common.tenant;

import java.util.UUID;

/**
 * Abstraction for looking up which tenant database a club uses.
 * Implemented differently per service:
 *   - tenant-management-service: direct JPA/JDBC query against micomm_control
 *   - email-service / sms-service: HTTP call to tenant-management-service,
 *     or a shared read-only JDBC connection to micomm_control
 */
public interface TenantRegistryClient {
    TenantDatabaseInfo findActiveTenantDatabase(UUID clubId);
}