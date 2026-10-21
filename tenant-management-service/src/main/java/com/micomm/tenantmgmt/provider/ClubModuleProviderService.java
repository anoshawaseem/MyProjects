package com.micomm.tenantmgmt.provider;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.micomm.tenantmgmt.module.ModuleDtos.ProductModuleDto;
import com.micomm.tenantmgmt.module.ModuleRepository;
import com.micomm.tenantmgmt.module.ModuleValidationException;
import com.micomm.tenantmgmt.provider.ClubModuleProviderRepository.ModuleProvider;

/**
 * Per-module provider accounts: a module of a club's product can send through its own account
 * instead of the product's. Removing a module's account puts it back on the product's provider.
 */
@Service
public class ClubModuleProviderService {

    private final ClubModuleProviderRepository repository;
    private final ModuleRepository moduleRepository;
    private final ProviderAccountRepository accountRepository;
    private final ProviderTypeRepository typeRepository;

    public ClubModuleProviderService(ClubModuleProviderRepository repository, ModuleRepository moduleRepository,
            ProviderAccountRepository accountRepository, ProviderTypeRepository typeRepository) {
        this.repository = repository;
        this.moduleRepository = moduleRepository;
        this.accountRepository = accountRepository;
        this.typeRepository = typeRepository;
    }

    public List<ModuleProvider> list(UUID clubId, UUID productId) {
        return repository.findByClubProduct(clubId, productId);
    }

    /** Sets the module's account for a channel; the account must be the club's, active, and of that channel. */
    public List<ModuleProvider> assign(UUID clubId, UUID productId, String moduleCode, String channel,
            UUID providerAccountId) {
        ProductModuleDto module = requireModule(productId, moduleCode);
        String ch = normalizeChannel(channel);

        ProviderAccount account = accountRepository.findById(providerAccountId)
                .filter(a -> a.getClubId().equals(clubId))
                .orElseThrow(() -> new ModuleValidationException("Provider account not found for this club"));
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new ModuleValidationException("Provider account '" + account.getName() + "' is not active");
        }
        String accountChannel = typeRepository.findById(account.getProviderTypeId())
                .map(ProviderType::getChannel)
                .orElseThrow(() -> new ModuleValidationException("Provider type not found"));
        if (!ch.equalsIgnoreCase(accountChannel)) {
            throw new ModuleValidationException("Provider account '" + account.getName() + "' is for "
                    + accountChannel + ", not " + ch);
        }

        repository.upsert(clubId, productId, module.id(), ch, providerAccountId);
        return list(clubId, productId);
    }

    /** The module goes back to the product's provider for this channel. */
    public List<ModuleProvider> clear(UUID clubId, UUID productId, String moduleCode, String channel) {
        ProductModuleDto module = requireModule(productId, moduleCode);
        repository.delete(clubId, productId, module.id(), normalizeChannel(channel));
        return list(clubId, productId);
    }

    private ProductModuleDto requireModule(UUID productId, String moduleCode) {
        if (moduleCode == null || moduleCode.isBlank()) {
            throw new ModuleValidationException("'moduleCode' is required");
        }
        return moduleRepository.findModule(productId, moduleCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ModuleValidationException("Module " + moduleCode + " is not a module of this product"));
    }

    private static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new ModuleValidationException("'channel' is required");
        }
        return channel.trim().toUpperCase(Locale.ROOT);
    }
}
