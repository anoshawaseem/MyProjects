package com.micomm.tenantmgmt.apikey;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.micomm.tenantmgmt.apikey.ApiKeyDtos.ApiKeyDto;
import com.micomm.tenantmgmt.apikey.ApiKeyDtos.VerifiedApiKeyDto;

/** Control-database access for api_keys. */
@Repository
public class ApiKeyRepository {

    private static final String COLUMNS =
            "id, club_id, name, key_prefix, created_at, expires_at, last_used_at, revoked_at";

    private static final RowMapper<ApiKeyDto> KEY = (rs, n) -> new ApiKeyDto(
            rs.getObject("id", UUID.class), rs.getObject("club_id", UUID.class), rs.getString("name"),
            rs.getString("key_prefix"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("last_used_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public ApiKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ApiKeyDto insert(UUID clubId, String name, String keyPrefix, String keyHash, UUID createdBy,
            OffsetDateTime expiresAt) {
        return jdbcTemplate.queryForObject("INSERT INTO api_keys (club_id, name, key_prefix, key_hash, created_by,"
                + " expires_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                KEY, clubId, name, keyPrefix, keyHash, createdBy, expiresAt);
    }

    public List<ApiKeyDto> findByClub(UUID clubId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM api_keys WHERE club_id = ? ORDER BY created_at DESC",
                KEY, clubId);
    }

    /** Returns false if the club has no such key or it was already revoked. */
    public boolean revoke(UUID clubId, UUID id) {
        return jdbcTemplate.update("UPDATE api_keys SET revoked_at = now()"
                + " WHERE id = ? AND club_id = ? AND revoked_at IS NULL", id, clubId) > 0;
    }

    /**
     * The key with this hash if it is usable now: not revoked, not expired, and its club active.
     * Records the use at most every five minutes, so busy keys don't write on every call.
     */
    public Optional<VerifiedApiKeyDto> verify(String keyHash) {
        Optional<VerifiedApiKeyDto> key = jdbcTemplate.query("""
                SELECT k.id, k.club_id, k.name, k.key_prefix
                FROM api_keys k
                JOIN clubs c ON c.id = k.club_id AND c.status = 'ACTIVE'
                WHERE k.key_hash = ? AND k.revoked_at IS NULL AND (k.expires_at IS NULL OR k.expires_at > now())
                """, (rs, n) -> new VerifiedApiKeyDto(rs.getObject("id", UUID.class),
                rs.getObject("club_id", UUID.class), rs.getString("name"), rs.getString("key_prefix")), keyHash)
                .stream().findFirst();
        key.ifPresent(k -> jdbcTemplate.update("UPDATE api_keys SET last_used_at = now() WHERE id = ?"
                + " AND (last_used_at IS NULL OR last_used_at < now() - interval '5 minutes')", k.apiKeyId()));
        return key;
    }
}
