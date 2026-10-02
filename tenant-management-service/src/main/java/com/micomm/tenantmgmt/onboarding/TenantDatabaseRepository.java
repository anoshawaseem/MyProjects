package com.micomm.tenantmgmt.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantDatabaseRepository extends JpaRepository<TenantDatabase, UUID> {
    List<TenantDatabase> findByClubId(UUID clubId);
    Optional<TenantDatabase> findByClubIdAndProductId(UUID clubId, UUID productId);
}