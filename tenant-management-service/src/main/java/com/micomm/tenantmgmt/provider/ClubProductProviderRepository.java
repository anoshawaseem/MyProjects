package com.micomm.tenantmgmt.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClubProductProviderRepository extends JpaRepository<ClubProductProvider, UUID> {
    List<ClubProductProvider> findByClubIdAndProductId(UUID clubId, UUID productId);
    Optional<ClubProductProvider> findByClubIdAndProductIdAndChannel(UUID clubId, UUID productId, String channel);
}