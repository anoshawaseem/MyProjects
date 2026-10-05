package com.micomm.tenantmgmt.provider;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ProviderResolutionService {

    private final ClubProductProviderRepository mappingRepo;
    private final ProviderAccountRepository accountRepo;
    private final ProviderTypeRepository typeRepo;

    public ProviderResolutionService(
            ClubProductProviderRepository mappingRepo,
            ProviderAccountRepository accountRepo,
            ProviderTypeRepository typeRepo) {
        this.mappingRepo = mappingRepo;
        this.accountRepo = accountRepo;
        this.typeRepo = typeRepo;
    }

    public ResolvedProviderDto resolve(UUID clubId, UUID productId, String channel) {
        ClubProductProvider mapping = mappingRepo
                .findByClubIdAndProductIdAndChannel(clubId, productId, channel.toUpperCase())
                .orElseThrow(() -> new ProviderNotConfiguredException(
                        "No " + channel + " provider configured for club " + clubId + " / product " + productId));

        if (!"ACTIVE".equals(mapping.getStatus())) {
            throw new ProviderNotConfiguredException("Provider mapping is not active for this club/product/channel");
        }

        ProviderAccount account = accountRepo.findById(mapping.getProviderAccountId())
                .orElseThrow(() -> new ProviderNotConfiguredException("Provider account not found"));

        if (!"ACTIVE".equals(account.getStatus())) {
            throw new ProviderNotConfiguredException("Provider account is not active");
        }

        ProviderType type = typeRepo.findById(account.getProviderTypeId())
                .orElseThrow(() -> new ProviderNotConfiguredException("Provider type not found"));

        return new ResolvedProviderDto(
                account.getId(),
                type.getCode(),
                account.getSecretReference(),
                account.getConfigJson()
        );
    }
}