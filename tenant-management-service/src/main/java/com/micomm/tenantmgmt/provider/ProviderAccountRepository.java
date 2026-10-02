package com.micomm.tenantmgmt.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProviderAccountRepository extends JpaRepository<ProviderAccount, UUID> {
    List<ProviderAccount> findByClubId(UUID clubId);
}