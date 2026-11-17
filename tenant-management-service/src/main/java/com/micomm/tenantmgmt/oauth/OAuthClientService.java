package com.micomm.tenantmgmt.oauth;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.micomm.common.security.ClientScopes;
import com.micomm.tenantmgmt.club.ClubRepository;
import com.micomm.tenantmgmt.oauth.OAuthClientDtos.OAuthClientDto;
import com.micomm.tenantmgmt.oauth.OAuthClientDtos.RegisterClientRequest;

/** Registers, lists and revokes a club's OAuth applications. */
@Service
public class OAuthClientService {

    private static final char[] ID_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray();
    private static final int ID_LENGTH = 16;

    private final SecureRandom random = new SecureRandom();
    private final OAuthClientRepository repository;
    private final ClubRepository clubRepository;

    public OAuthClientService(OAuthClientRepository repository, ClubRepository clubRepository) {
        this.repository = repository;
        this.clubRepository = clubRepository;
    }

    public OAuthClientDto register(UUID clubId, RegisterClientRequest request, UUID createdBy) {
        if (!clubRepository.existsById(clubId)) {
            throw new IllegalArgumentException("Club not found: " + clubId);
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : request.scopes()) {
            String s = scope.trim();
            if (!ClientScopes.ALL.contains(s)) {
                throw new InvalidClientRegistrationException("Unknown scope '" + s + "'; allowed: "
                        + String.join(", ", ClientScopes.ALL));
            }
            scopes.add(s);
        }
        PublicKeys.ParsedKey key;
        try {
            key = PublicKeys.parse(request.publicKeyPem());
        } catch (IllegalArgumentException ex) {
            throw new InvalidClientRegistrationException(ex.getMessage());
        }
        return repository.insert(clubId, "mcc_" + randomId(), request.name().trim(), request.publicKeyPem().trim(),
                key.algorithm(), List.copyOf(scopes), createdBy, request.expiresAt());
    }

    public List<OAuthClientDto> list(UUID clubId) {
        return repository.findByClub(clubId);
    }

    /** Existing access tokens keep working until they expire (at most the token lifetime). */
    public void revoke(UUID clubId, UUID id) {
        if (!repository.revoke(clubId, id)) {
            throw new IllegalArgumentException("Application not found or already revoked: " + id);
        }
    }

    private String randomId() {
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 400: the registration request is not acceptable (bad key, unknown scope). */
    public static class InvalidClientRegistrationException extends RuntimeException {
        public InvalidClientRegistrationException(String message) {
            super(message);
        }
    }
}
