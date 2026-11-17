package com.micomm.email.channel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.micomm.email.provider.UnsupportedProviderException;

/** The SMS / WhatsApp senders, by provider type code. */
@Component
public class TextMessageProviders {

    private final Map<String, TextMessageProvider> byCode;

    public TextMessageProviders(List<TextMessageProvider> providers) {
        this.byCode = providers.stream().collect(Collectors.toMap(TextMessageProvider::providerTypeCode,
                Function.identity()));
    }

    public Optional<TextMessageProvider> find(String providerTypeCode, String channel) {
        return Optional.ofNullable(byCode.get(providerTypeCode)).filter(p -> p.channel().equals(channel));
    }

    public TextMessageProvider get(String providerTypeCode, String channel) {
        return find(providerTypeCode, channel).orElseThrow(() -> new UnsupportedProviderException(
                "No " + channel + " sender for provider type: " + providerTypeCode));
    }
}
