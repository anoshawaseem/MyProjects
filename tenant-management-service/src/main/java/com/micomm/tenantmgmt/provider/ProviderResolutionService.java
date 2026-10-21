package com.micomm.tenantmgmt.provider;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class ProviderResolutionService {

    private final ClubProductProviderRepository mappingRepo;
    private final ClubModuleProviderRepository moduleProviderRepo;
    private final ProviderAccountRepository accountRepo;
    private final ProviderTypeRepository typeRepo;

    public ProviderResolutionService(
            ClubProductProviderRepository mappingRepo,
            ClubModuleProviderRepository moduleProviderRepo,
            ProviderAccountRepository accountRepo,
            ProviderTypeRepository typeRepo) {
        this.mappingRepo = mappingRepo;
        this.moduleProviderRepo = moduleProviderRepo;
        this.accountRepo = accountRepo;
        this.typeRepo = typeRepo;
    }

    /**
     * The provider for a send from {@code moduleCode} (null: no module): the module's own active
     * account if it has one for this channel, otherwise the product's.
     */
    public ResolvedProviderDto resolve(UUID clubId, UUID productId, String channel, String moduleCode) {
        if (moduleCode != null && !moduleCode.isBlank()) {
            Optional<ProviderAccount> moduleAccount = moduleProviderRepo
                    .findAccountId(clubId, productId, moduleCode.trim().toUpperCase(), channel.toUpperCase())
                    .flatMap(accountRepo::findById)
                    // A disabled account no longer applies; the module falls back to the product's.
                    .filter(a -> "ACTIVE".equals(a.getStatus()));
            if (moduleAccount.isPresent()) {
                return toDto(moduleAccount.get());
            }
        }
        return resolve(clubId, productId, channel);
    }

    /** The product's provider for a channel. */
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

        return toDto(account);
    }

    private ResolvedProviderDto toDto(ProviderAccount account) {
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
