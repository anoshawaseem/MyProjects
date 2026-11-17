package com.micomm.tenantmgmt.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.security.JwtProperties;
import com.micomm.common.security.JwtTokenProvider;
import com.micomm.tenantmgmt.audit.ClubAuditLog;
import com.micomm.tenantmgmt.oauth.OAuthClientDtos.ActiveClient;
import com.micomm.tenantmgmt.oauth.OAuthTokenService.OAuthException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class OAuthTokenServiceTest {

    private static final String AUD = "http://localhost:8081/oauth/token";
    private static final String CLIENT_ID = "mcc_testclient0001";
    private static final UUID CLUB = UUID.randomUUID();

    private final OAuthClientRepository repository = mock(OAuthClientRepository.class);
    private JwtTokenProvider jwtTokenProvider;
    private OAuthTokenService service;
    private KeyPair rsa;

    @BeforeEach
    void setUp() throws Exception {
        JwtProperties props = new JwtProperties();
        props.setSecret("dGhpc2lzYXRlc3RzZWNyZXRrZXlmb3Jqd3RzaWduaW5nMzJieXRlcw==");
        jwtTokenProvider = new JwtTokenProvider(props);
        service = new OAuthTokenService(repository, jwtTokenProvider, mock(ClubAuditLog.class), new ObjectMapper(),
                List.of(AUD), Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofSeconds(30));

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        rsa = gen.generateKeyPair();
        register(rsa, "RS256", List.of("email:send", "email:read"));
        when(repository.markAssertionUsed(eq(CLIENT_ID), anyString(), any())).thenReturn(true);
    }

    private void register(KeyPair pair, String alg, List<String> scopes) {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        when(repository.findActive(CLIENT_ID)).thenReturn(Optional.of(
                new ActiveClient(UUID.randomUUID(), CLUB, CLIENT_ID, "MiClub backend", pem, alg, scopes)));
    }

    private static String assertion(PrivateKey key, String aud, Instant iat, Instant exp, String jti) {
        return Jwts.builder().issuer(CLIENT_ID).subject(CLIENT_ID).audience().add(aud).and()
                .issuedAt(Date.from(iat)).expiration(Date.from(exp)).id(jti).signWith(key).compact();
    }

    private static String validAssertion(PrivateKey key) {
        Instant now = Instant.now();
        return assertion(key, AUD, now, now.plusSeconds(60), UUID.randomUUID().toString());
    }

    private OAuthTokenService.IssuedToken issue(String assertion, String scope) {
        return service.issue("client_credentials", OAuthTokenService.JWT_BEARER, assertion, null, scope);
    }

    @Test
    void validRsaAssertionGetsAClubTokenWithTheClientsScopes() {
        var token = issue(validAssertion(rsa.getPrivate()), null);

        Claims claims = jwtTokenProvider.parseClaims(token.accessToken());
        assertThat(jwtTokenProvider.isClientToken(claims)).isTrue();
        assertThat(jwtTokenProvider.getClubId(claims)).isEqualTo(CLUB);
        assertThat(jwtTokenProvider.getScopes(claims)).containsExactly("email:send", "email:read");
        assertThat(token.expiresIn()).isEqualTo(900);
    }

    @Test
    void ecP256KeysWorkToo() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ec = gen.generateKeyPair();
        register(ec, "ES256", List.of("email:send"));

        assertThat(issue(validAssertion(ec.getPrivate()), null).scope()).isEqualTo("email:send");
    }

    @Test
    void canAskForFewerScopesButNotMore() {
        assertThat(issue(validAssertion(rsa.getPrivate()), "email:send").scope()).isEqualTo("email:send");
        assertThatThrownBy(() -> issue(validAssertion(rsa.getPrivate()), "templates:write"))
                .isInstanceOf(OAuthException.class).extracting("error").isEqualTo("invalid_scope");
    }

    @Test
    void assertionSignedWithAnotherKeyIsRefused() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        PrivateKey attacker = gen.generateKeyPair().getPrivate();

        assertThatThrownBy(() -> issue(validAssertion(attacker), null))
                .isInstanceOf(OAuthException.class).extracting("error").isEqualTo("invalid_client");
    }

    @Test
    void hmacSignedAssertionIsRefused() {
        // An attacker who knows only public values must not be able to switch to a shared-secret algorithm.
        Instant now = Instant.now();
        String hs = Jwts.builder().issuer(CLIENT_ID).subject(CLIENT_ID).audience().add(AUD).and()
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60))).id("x")
                .signWith(Keys.hmacShaKeyFor(new byte[32])).compact();

        assertThatThrownBy(() -> issue(hs, null)).isInstanceOf(OAuthException.class)
                .hasMessageContaining("RS256");
    }

    @Test
    void wrongAudienceExpiredAndTooLongLivedAssertionsAreRefused() {
        Instant now = Instant.now();
        PrivateKey key = rsa.getPrivate();
        assertThatThrownBy(() -> issue(assertion(key, "https://evil.example/token", now, now.plusSeconds(60), "a"), null))
                .isInstanceOf(OAuthException.class).hasMessageContaining("aud");
        assertThatThrownBy(() -> issue(assertion(key, AUD, now.minusSeconds(600), now.minusSeconds(300), "b"), null))
                .isInstanceOf(OAuthException.class).hasMessageContaining("expired");
        assertThatThrownBy(() -> issue(assertion(key, AUD, now, now.plusSeconds(3600), "c"), null))
                .isInstanceOf(OAuthException.class).hasMessageContaining("at most");
    }

    @Test
    void replayedAssertionIsRefused() {
        String once = validAssertion(rsa.getPrivate());
        issue(once, null);
        when(repository.markAssertionUsed(eq(CLIENT_ID), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> issue(once, null)).isInstanceOf(OAuthException.class)
                .hasMessageContaining("already used");
    }

    @Test
    void unknownOrRevokedClientIsRefused() {
        when(repository.findActive(CLIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> issue(validAssertion(rsa.getPrivate()), null))
                .isInstanceOf(OAuthException.class).extracting("error").isEqualTo("invalid_client");
    }

    @Test
    void weakOrWrongKeysCannotBeRegistered() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(1024);
        String weak = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(gen.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";

        assertThatThrownBy(() -> PublicKeys.parse(weak)).hasMessageContaining("2048");
        assertThatThrownBy(() -> PublicKeys.parse("-----BEGIN EC PRIVATE KEY-----\nabc\n-----END EC PRIVATE KEY-----"))
                .hasMessageContaining("PRIVATE key");
        assertThatThrownBy(() -> PublicKeys.parse("ssh-rsa AAAAB3Nza user@host"))
                .hasMessageContaining("SSH keys are not supported");
    }
}
