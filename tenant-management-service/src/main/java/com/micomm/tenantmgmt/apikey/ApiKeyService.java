package com.micomm.tenantmgmt.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.micomm.tenantmgmt.apikey.ApiKeyDtos.ApiKeyDto;
import com.micomm.tenantmgmt.apikey.ApiKeyDtos.CreateApiKeyRequest;
import com.micomm.tenantmgmt.apikey.ApiKeyDtos.CreatedApiKeyDto;
import com.micomm.tenantmgmt.apikey.ApiKeyDtos.VerifiedApiKeyDto;
import com.micomm.tenantmgmt.club.ClubRepository;

/**
 * API keys for applications that call MiComm on behalf of one club.
 *
 * A key looks like {@code mck_<prefix>_<secret>}: the prefix is public (it identifies the key in
 * lists), the secret is 256 random bits. Only the SHA-256 of the whole key is stored; with that
 * much randomness a plain hash is enough (no slow password hash needed), and it lets
 * email-service send the hash instead of the key when it asks whether a key is valid.
 */
@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "mck_";
    private static final char[] PREFIX_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray();
    private static final int PREFIX_LENGTH = 10;
    private static final int SECRET_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final ApiKeyRepository apiKeyRepository;
    private final ClubRepository clubRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, ClubRepository clubRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.clubRepository = clubRepository;
    }

    public CreatedApiKeyDto create(UUID clubId, CreateApiKeyRequest request, UUID createdBy) {
        if (!clubRepository.existsById(clubId)) {
            throw new IllegalArgumentException("Club not found: " + clubId);
        }
        String prefix = KEY_PREFIX + randomPrefix();
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        String key = prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        ApiKeyDto saved = apiKeyRepository.insert(clubId, request.name().trim(), prefix, sha256Hex(key), createdBy,
                request.expiresAt());
        return new CreatedApiKeyDto(saved, key);
    }

    public List<ApiKeyDto> list(UUID clubId) {
        return apiKeyRepository.findByClub(clubId);
    }

    public void revoke(UUID clubId, UUID id) {
        if (!apiKeyRepository.revoke(clubId, id)) {
            throw new IllegalArgumentException("API key not found or already revoked: " + id);
        }
    }

    public Optional<VerifiedApiKeyDto> verify(String keyHash) {
        return apiKeyRepository.verify(keyHash);
    }

    private String randomPrefix() {
        StringBuilder sb = new StringBuilder(PREFIX_LENGTH);
        for (int i = 0; i < PREFIX_LENGTH; i++) {
            sb.append(PREFIX_ALPHABET[random.nextInt(PREFIX_ALPHABET.length)]);
        }
        return sb.toString();
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
