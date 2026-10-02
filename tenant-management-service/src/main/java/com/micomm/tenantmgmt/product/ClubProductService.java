package com.micomm.tenantmgmt.product;

import com.micomm.tenantmgmt.club.Club;
import com.micomm.tenantmgmt.club.ClubRepository;
import com.micomm.tenantmgmt.onboarding.TenantOnboardingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ClubProductService {

    private final ClubProductRepository clubProductRepository;
    private final ClubRepository clubRepository;
    private final TenantOnboardingService tenantOnboardingService;

    public ClubProductService(ClubProductRepository clubProductRepository,
                                ClubRepository clubRepository,
                                TenantOnboardingService tenantOnboardingService) {
        this.clubProductRepository = clubProductRepository;
        this.clubRepository = clubRepository;
        this.tenantOnboardingService = tenantOnboardingService;
    }

    public List<ClubProduct> getProductsForClub(UUID clubId) {
        return clubProductRepository.findByClubId(clubId);
    }

    @Transactional
    public List<ClubProduct> setProductsForClub(UUID clubId, Set<UUID> productIds) {
        List<ClubProduct> existing = clubProductRepository.findByClubId(clubId);
        Set<UUID> existingProductIds = existing.stream().map(ClubProduct::getProductId).collect(Collectors.toSet());

        // Determine which products were removed (to drop their schemas after deletion)
        Set<UUID> removedProductIds = existingProductIds.stream()
                .filter(id -> !productIds.contains(id))
                .collect(Collectors.toSet());

        // Remove products no longer selected
        clubProductRepository.deleteByClubIdAndProductIdNotIn(clubId, productIds.stream().toList());

        // Add newly selected products not already present
        for (UUID productId : productIds) {
            if (!existingProductIds.contains(productId)) {
                ClubProduct cp = new ClubProduct();
                cp.setClubId(clubId);
                cp.setProductId(productId);
                cp.setStatus("ACTIVE");
                clubProductRepository.save(cp);
            }
        }

        List<ClubProduct> updated = clubProductRepository.findByClubId(clubId);

        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new IllegalArgumentException("Club not found: " + clubId));

        // Drop schemas for removed products (destroys their data)
        for (UUID removedProductId : removedProductIds) {
            tenantOnboardingService.dropSchemaForProduct(clubId, removedProductId);
        }

        // If the club has already completed onboarding, provision schemas for any newly added products immediately
        if ("ACTIVE".equals(club.getStatus()) && !updated.isEmpty()) {
            tenantOnboardingService.provisionSchemasForProducts(club, updated);
        }

        return updated;
    }
}