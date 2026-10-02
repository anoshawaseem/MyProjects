package com.micomm.tenantmgmt.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClubProductRepository extends JpaRepository<ClubProduct, UUID> {
    List<ClubProduct> findByClubId(UUID clubId);
    void deleteByClubIdAndProductIdNotIn(UUID clubId, List<UUID> productIds);
}