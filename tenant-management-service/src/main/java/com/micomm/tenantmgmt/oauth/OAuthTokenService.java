package com.micomm.tenantmgmt.oauth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.security.JwtTokenProvider;
import com.micomm.common.security.MicommActor;
import com.micomm.tenantmgmt.audit.ClubAuditLog;
import com.micomm.tenantmgmt.oauth.OAuthClientDtos.ActiveClient;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

/**
 * The OAuth 2.0 client credentials grant with private key JWT client authentication
 * (RFC 6749 section 4.4, RFC 7523 / RFC 7521). The application proves who it is by signing a
 * short-lived assertion with its private key; MiComm checks it against the registered public key
 * and returns a short-lived access token for the application's club and scopes.
 *
 * Assertion rules: signed with the client's registered algorithm (RS256 or ES256, never HMAC or
 * "none"); iss = sub = client_id; aud = this token endpoint's URL; exp, iat and jti present;
 * lives at most {@code micomm.oauth.max-assertion-lifetime}; each jti is accepted once.
 */
@Service
public class OAuthTokenService {

    public static final String CLIENT_CREDENTIALS = "client_credentials";
    public static final String JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    /** What the token endpoint returns on success (RFC 6749 section 5.1). */
    public record IssuedToken(String accessToken, long expiresIn, String scope) {
    }

    private final OAuthClientRepository repository;
    private final JwtTokenProvider jwtTokenProvider;
    private final ClubAuditLog clubAuditLog;
    private final ObjectMapper objectMapper;
    private final List<String> audiences;
    private final Duration tokenLifetime;
    private final Duration maxAssertionLifetime;
    private final Duration clockSkew;

    public OAuthTokenService(OAuthClientRepository repository, JwtTokenProvider jwtTokenProvider,
            ClubAuditLog clubAuditLog, ObjectMapper objectMapper,
            // The token endpoint's public URL(s); assertions must name one of them as "aud".
            @Value("${micomm.oauth.token-audience:http://localhost:8081/oauth/token}") List<String> audiences,
            @Value("${micomm.oauth.token-lifetime:15m}") Duration tokenLifetime,
            @Value("${micomm.oauth.max-assertion-lifetime:5m}") Duration maxAssertionLifetime,
            @Value("${micomm.oauth.clock-skew:30s}") Duration clockSkew) {
        this.repository = repository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.clubAuditLog = clubAuditLog;
        this.objectMapper = objectMapper;
        this.audiences = audiences;
        this.tokenLifetime = tokenLifetime;
        this.maxAssertionLifetime = maxAssertionLifetime;
        this.clockSkew = clockSkew;
    }

    public IssuedToken issue(String grantType, String assertionType, String assertion, String clientIdParam,
            String requestedScope) {
        if (!CLIENT_CREDENTIALS.equals(grantType)) {
            throw new OAuthException(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                    "grant_type must be " + CLIENT_CREDENTIALS);
        }
        if (!JWT_BEARER.equals(assertionType) || assertion == null || assertion.isBlank()) {
            throw new OAuthException(HttpStatus.BAD_REQUEST, "invalid_request",
                    "Send client_assertion_type=" + JWT_BEARER + " and a signed client_assertion");
        }

        // Read (not yet trust) who claims to be calling, to find the key to check the signature with.
        JsonNode header = decodePart(assertion, 0);
        JsonNode payload = decodePart(assertion, 1);
        String clientId = payload.path("iss").asText(null);
        if (clientId == null || !clientId.equals(payload.path("sub").asText(null))) {
            throw invalidClient("iss and sub must both be the client_id");
        }
        if (clientIdParam != null && !clientIdParam.isBlank() && !clientIdParam.equals(clientId)) {
            throw invalidClient("client_id does not match the assertion");
        }

        Optional<ActiveClient> found = repository.findActive(clientId);
        if (found.isEmpty()) {
            denied(clientId, "unknown, revoked or expired client");
            throw invalidClient("Unknown, revoked or expired client");
        }
        ActiveClient client = found.get();
        try {
            String alg = header.path("alg").asText("");
            if (!client.keyAlgorithm().equals(alg)) {
                throw invalidClient("Assertion must be signed with " + client.keyAlgorithm());
            }
            Claims claims = verify(assertion, client);
            List<String> scopes = grantedScopes(client, requestedScope);

            if (!repository.markAssertionUsed(clientId, claims.getId(),
                    claims.getExpiration().toInstant().atOffset(ZoneOffset.UTC))) {
                throw invalidClient("This assertion was already used; sign a new one with a new jti");
            }

            String token = jwtTokenProvider.generateClientToken(client.id(), client.clientId(), client.name(),
                    client.clubId(), scopes, tokenLifetime);
            repository.touchLastUsed(client.id());
            clubAuditLog.record(AuditEvent.of(client.clubId(), null, AuditActions.OAUTH_TOKEN_ISSUED, "OAUTH_CLIENT",
                    client.id()).with("scopes", scopes).by(actor(client)));
            return new IssuedToken(token, tokenLifetime.toSeconds(), String.join(" ", scopes));
        } catch (OAuthException ex) {
            clubAuditLog.record(AuditEvent.of(client.clubId(), null, AuditActions.OAUTH_TOKEN_DENIED, "OAUTH_CLIENT",
                    client.id()).with("reason", ex.getMessage()).by(actor(client)));
            throw ex;
        }
    }

    private Claims verify(String assertion, ActiveClient client) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(PublicKeys.parse(client.publicKeyPem()).key())
                    .clockSkewSeconds(clockSkew.toSeconds())
                    .requireIssuer(client.clientId())
                    .requireSubject(client.clientId())
                    .build()
                    .parseSignedClaims(assertion)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw invalidClient("Assertion is not valid: " + ex.getMessage());
        }

        Set<String> aud = claims.getAudience();
        if (aud == null || aud.stream().noneMatch(audiences::contains)) {
            throw invalidClient("aud must be " + audiences.get(0));
        }
        if (claims.getExpiration() == null || claims.getIssuedAt() == null) {
            throw invalidClient("Assertion must have exp and iat");
        }
        Instant iat = claims.getIssuedAt().toInstant();
        Instant exp = claims.getExpiration().toInstant();
        Instant now = Instant.now();
        if (Duration.between(iat, exp).compareTo(maxAssertionLifetime) > 0
                || Duration.between(now, exp).compareTo(maxAssertionLifetime.plus(clockSkew)) > 0) {
            throw invalidClient("Assertion may live at most " + maxAssertionLifetime.toSeconds() + " seconds");
        }
        if (iat.isAfter(now.plus(clockSkew))) {
            throw invalidClient("Assertion iat is in the future");
        }
        String jti = claims.getId();
        if (jti == null || jti.isBlank() || jti.length() > 255) {
            throw invalidClient("Assertion must have a unique jti (at most 255 characters)");
        }
        return claims;
    }

    /** The requested scopes (default: all the client has), which must all be the client's. */
    private static List<String> grantedScopes(ActiveClient client, String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank()) {
            return client.scopes();
        }
        Set<String> requested = new LinkedHashSet<>(List.of(requestedScope.trim().split("\\s+")));
        if (!client.scopes().containsAll(requested)) {
            throw new OAuthException(HttpStatus.BAD_REQUEST, "invalid_scope",
                    "This application may only request: " + String.join(" ", client.scopes()));
        }
        return List.copyOf(requested);
    }

    private JsonNode decodePart(String jwt, int index) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw invalidClient("client_assertion must be a signed JWT");
        }
        try {
            return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(parts[index]), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw invalidClient("client_assertion is not a readable JWT");
        }
    }

    /** A refused handshake for a client we know goes to its club's audit log. */
    private void denied(String clientId, String reason) {
        repository.findClubOf(clientId).ifPresent(clubId -> clubAuditLog.record(
                AuditEvent.of(clubId, null, AuditActions.OAUTH_TOKEN_DENIED, "OAUTH_CLIENT", clientId)
                        .with("reason", reason).by(MicommActor.SYSTEM)));
    }

    private static MicommActor actor(ActiveClient client) {
        return MicommActor.apiClient(client.id(), client.name(), client.clientId());
    }

    private static OAuthException invalidClient(String description) {
        return new OAuthException(HttpStatus.UNAUTHORIZED, "invalid_client", description);
    }

    /** An OAuth error response (RFC 6749 section 5.2). */
    public static class OAuthException extends RuntimeException {
        private final HttpStatus status;
        private final String error;

        public OAuthException(HttpStatus status, String error, String description) {
            super(description);
            this.status = status;
            this.error = error;
        }

        public HttpStatus status() {
            return status;
        }

        public String error() {
            return error;
        }
    }
}
