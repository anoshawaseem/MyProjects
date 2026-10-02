package com.micomm.tenantmgmt.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProviderTypeRepository extends JpaRepository<ProviderType, UUID> {
}