package com.micomm.tenantmgmt.provider;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ClubProductProviderService {

    private final ClubProductProviderRepository mappingRepository;

    public ClubProductProviderService(ClubProductProviderRepository mappingRepository) {
        this.mappingRepository = mappingRepository;
    }

    public List<ClubProductProvider> getForClubProduct(UUID clubId, UUID productId) {
        return mappingRepository.findByClubIdAndProductId(clubId, productId);
    }

    public ClubProductProvider assign(UUID clubId, UUID productId, String channel, UUID providerAccountId) {
        ClubProductProvider mapping = mappingRepository
                .findByClubIdAndProductIdAndChannel(clubId, productId, channel)
                .orElseGet(ClubProductProvider::new);

        mapping.setClubId(clubId);
        mapping.setProductId(productId);
        mapping.setChannel(channel);
        mapping.setProviderAccountId(providerAccountId);
        mapping.setDefault(true);
        mapping.setStatus("ACTIVE");

        return mappingRepository.save(mapping);
    }
}