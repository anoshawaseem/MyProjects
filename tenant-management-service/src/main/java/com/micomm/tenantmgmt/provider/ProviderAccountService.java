package com.micomm.tenantmgmt.provider;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ProviderAccountService {

    private final ProviderAccountRepository providerAccountRepository;

    public ProviderAccountService(ProviderAccountRepository providerAccountRepository) {
        this.providerAccountRepository = providerAccountRepository;
    }

    public List<ProviderAccount> listForClub(UUID clubId) {
        return providerAccountRepository.findByClubId(clubId);
    }

    public ProviderAccount create(UUID clubId, UUID providerTypeId, String name, String secretReference, String configJson) {
        ProviderAccount account = new ProviderAccount();
        account.setClubId(clubId);
        account.setProviderTypeId(providerTypeId);
        account.setName(name);
        account.setSecretReference(secretReference);
        account.setConfigJson(configJson);
        account.setStatus("ACTIVE");
        return providerAccountRepository.save(account);
    }

    public void delete(UUID id) {
        ProviderAccount account = providerAccountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Provider account not found: " + id));
        account.setStatus("DISABLED");
        providerAccountRepository.save(account);
    }
}