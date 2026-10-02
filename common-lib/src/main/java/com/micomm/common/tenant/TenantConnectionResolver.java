package com.micomm.common.tenant;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches a DataSource per club_id.
 * Looks up the tenant's database connection details from the
 * control DB's tenant_databases table (via TenantRegistryClient),
 * then builds/caches a pooled DataSource for that tenant.
 */
public class TenantConnectionResolver {

    private final TenantRegistryClient registryClient; // calls control DB or its API
    private final Map<UUID, DataSource> dataSourceCache = new ConcurrentHashMap<>();

    public TenantConnectionResolver(TenantRegistryClient registryClient) {
        this.registryClient = registryClient;
    }

    public DataSource resolve(UUID clubId) {
        return dataSourceCache.computeIfAbsent(clubId, id -> {
            TenantDatabaseInfo info = registryClient.findActiveTenantDatabase(id);
            return DataSourceFactory.build(info); // HikariCP pool pointed at info.databaseIdentifier
        });
    }

    public void evict(UUID clubId) {
        dataSourceCache.remove(clubId);
    }
}