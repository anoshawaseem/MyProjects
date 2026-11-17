package com.micomm.tenantmgmt.oauth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.micomm.tenantmgmt.oauth.OAuthClientDtos.ActiveClient;
import com.micomm.tenantmgmt.oauth.OAuthClientDtos.OAuthClientDto;

/** Control-database access for oauth_clients and oauth_used_assertions. */
@Repository
public class OAuthClientRepository {

    private static final String COLUMNS = "id, club_id, client_id, name, key_algorithm, scopes, public_key_pem,"
            + " created_at, expires_at, last_used_at, revoked_at";

    private static final RowMapper<OAuthClientDto> CLIENT = (rs, n) -> new OAuthClientDto(
            rs.getObject("id", UUID.class), rs.getObject("club_id", UUID.class), rs.getString("client_id"),
            rs.getString("name"), rs.getString("key_algorithm"), splitScopes(rs.getString("scopes")),
            rs.getString("public_key_pem"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("last_used_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public OAuthClientRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OAuthClientDto insert(UUID clubId, String clientId, String name, String publicKeyPem, String keyAlgorithm,
            List<String> scopes, UUID createdBy, OffsetDateTime expiresAt) {
        return jdbcTemplate.queryForObject("INSERT INTO oauth_clients (club_id, client_id, name, public_key_pem,"
                + " key_algorithm, scopes, created_by, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                CLIENT, clubId, clientId, name, publicKeyPem, keyAlgorithm, String.join(" ", scopes), createdBy,
                expiresAt);
    }

    public List<OAuthClientDto> findByClub(UUID clubId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM oauth_clients WHERE club_id = ?"
                + " ORDER BY created_at DESC", CLIENT, clubId);
    }

    public boolean revoke(UUID clubId, UUID id) {
        return jdbcTemplate.update("UPDATE oauth_clients SET revoked_at = now()"
                + " WHERE id = ? AND club_id = ? AND revoked_at IS NULL", id, clubId) > 0;
    }

    /** The client if it may get tokens now: not revoked, not expired, and its club active. */
    public Optional<ActiveClient> findActive(String clientId) {
        return jdbcTemplate.query("""
                SELECT o.id, o.club_id, o.client_id, o.name, o.public_key_pem, o.key_algorithm, o.scopes
                FROM oauth_clients o
                JOIN clubs c ON c.id = o.club_id AND c.status = 'ACTIVE'
                WHERE o.client_id = ? AND o.revoked_at IS NULL AND (o.expires_at IS NULL OR o.expires_at > now())
                """, (rs, n) -> new ActiveClient(rs.getObject("id", UUID.class), rs.getObject("club_id", UUID.class),
                rs.getString("client_id"), rs.getString("name"), rs.getString("public_key_pem"),
                rs.getString("key_algorithm"), splitScopes(rs.getString("scopes"))), clientId).stream().findFirst();
    }

    /** Club of a client id, active or not (to record a refused handshake in the right audit log). */
    public Optional<UUID> findClubOf(String clientId) {
        return jdbcTemplate.queryForList("SELECT club_id FROM oauth_clients WHERE client_id = ?", UUID.class,
                clientId).stream().findFirst();
    }

    /**
     * Records an assertion as used. Returns false if this (client, jti) was already used, i.e.
     * the assertion is being replayed.
     */
    public boolean markAssertionUsed(String clientId, String jti, OffsetDateTime expiresAt) {
        return jdbcTemplate.update("INSERT INTO oauth_used_assertions (client_id, jti, expires_at) VALUES (?, ?, ?)"
                + " ON CONFLICT (client_id, jti) DO NOTHING", clientId, jti, expiresAt) == 1;
    }

    public void touchLastUsed(UUID id) {
        jdbcTemplate.update("UPDATE oauth_clients SET last_used_at = now() WHERE id = ?", id);
    }

    public int deleteExpiredAssertions() {
        return jdbcTemplate.update("DELETE FROM oauth_used_assertions WHERE expires_at < now() - interval '1 hour'");
    }

    private static List<String> splitScopes(String scopes) {
        return scopes == null || scopes.isBlank() ? List.of() : List.of(scopes.trim().split("\\s+"));
    }
}
