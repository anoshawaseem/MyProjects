package com.micomm.tenantmgmt.oauth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.tenantmgmt.oauth.OAuthTokenService.IssuedToken;
import com.micomm.tenantmgmt.oauth.OAuthTokenService.OAuthException;

/**
 * POST /oauth/token: the OAuth 2.0 token endpoint. Standard form request and JSON response
 * (not MiComm's ApiResponse envelope), so ordinary OAuth client libraries work with it.
 *
 * Request (application/x-www-form-urlencoded):
 * grant_type=client_credentials
 * &client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
 * &client_assertion=<JWT signed with the application's private key>
 * [&scope=email:send email:read]
 */
@RestController
public class OAuthTokenController {

    private final OAuthTokenService tokenService;

    public OAuthTokenController(OAuthTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping(path = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(name = "client_assertion_type", required = false) String assertionType,
            @RequestParam(name = "client_assertion", required = false) String assertion,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "scope", required = false) String scope) {
        IssuedToken token = tokenService.issue(grantType, assertionType, assertion, clientId, scope);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token.accessToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", token.expiresIn());
        body.put("scope", token.scope());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }

    @ExceptionHandler(OAuthException.class)
    public ResponseEntity<Map<String, String>> oauthError(OAuthException ex) {
        return ResponseEntity.status(ex.status()).cacheControl(CacheControl.noStore())
                .body(Map.of("error", ex.error(), "error_description", ex.getMessage()));
    }
}
