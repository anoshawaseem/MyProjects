package com.micomm.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final String TOKEN_USE = "token_use";
    private static final String CLIENT_TOKEN_USE = "client";

    private final SecretKey signingKey;
    private final JwtProperties properties;

    @Autowired
    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes());
    }

    public String generateAccessToken(UUID userId, String role, UUID clubId) {
        return generateAccessToken(userId, role, clubId, null);
    }

    /** email is carried so other services can name the user in audit logs without a lookup. */
    public String generateAccessToken(UUID userId, String role, UUID clubId, String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + properties.getAccessTokenExpiryMinutes() * 60_000);

        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey);

        if (clubId != null) {
            builder.claim("club_id", clubId.toString());
        }
        if (email != null) {
            builder.claim("email", email);
        }

        return builder.compact();
    }

    /**
     * An access token for an OAuth application (client): it acts for one club with only the given
     * scopes, and is marked token_use=client so services can tell it from a user's token.
     */
    public String generateClientToken(UUID clientUuid, String clientId, String clientName, UUID clubId,
            Collection<String> scopes, Duration lifetime) {
        Date now = new Date();
        return Jwts.builder()
                .subject(clientUuid.toString())
                .claim("role", "TENANT_ADMIN")
                .claim("club_id", clubId.toString())
                .claim(TOKEN_USE, CLIENT_TOKEN_USE)
                .claim("client_id", clientId)
                .claim("name", clientName)
                .claim("scope", String.join(" ", scopes))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + lifetime.toMillis()))
                .signWith(signingKey)
                .compact();
    }

    public boolean isClientToken(Claims claims) {
        return CLIENT_TOKEN_USE.equals(claims.get(TOKEN_USE, String.class));
    }

    /** The scopes of a client token (empty for user tokens). */
    public List<String> getScopes(Claims claims) {
        String scope = claims.get("scope", String.class);
        return scope == null || scope.isBlank() ? List.of() : List.of(scope.trim().split("\\s+"));
    }

    public String getClientId(Claims claims) {
        return claims.get("client_id", String.class);
    }

    public String getClientName(Claims claims) {
        return claims.get("name", String.class);
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID getUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public String getRole(Claims claims) {
        return claims.get("role", String.class);
    }

    public UUID getClubId(Claims claims) {
        String clubId = claims.get("club_id", String.class);
        return clubId != null ? UUID.fromString(clubId) : null;
    }

    /** Null for tokens issued before the email claim was added. */
    public String getEmail(Claims claims) {
        return claims.get("email", String.class);
    }
}