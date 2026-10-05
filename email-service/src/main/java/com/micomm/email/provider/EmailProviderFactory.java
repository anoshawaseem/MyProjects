package com.micomm.email.provider;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class EmailProviderFactory {

    private final Map<String, EmailProvider> providersByCode;

    public EmailProviderFactory(List<EmailProvider> providers) {
        this.providersByCode = providers.stream()
                .collect(Collectors.toMap(EmailProvider::providerTypeCode, p -> p));
    }

    public EmailProvider get(String providerTypeCode) {
        EmailProvider provider = providersByCode.get(providerTypeCode);
        if (provider == null) {
            throw new UnsupportedProviderException("No email provider registered for type: " + providerTypeCode);
        }
        return provider;
    }
}