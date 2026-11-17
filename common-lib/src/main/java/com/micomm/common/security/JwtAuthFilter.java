package com.micomm.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Validates JWTs on every request and populates a MicommAuthenticatedUser
 * into the Spring Security context. Shared by tenant-management-service,
 * email-service, and sms-service so all three enforce identical auth rules.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final boolean acceptClientTokens;

    /** Accepts user tokens only; applications' (OAuth client) tokens are ignored. */
    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider) {
        this(jwtTokenProvider, false);
    }

    /**
     * @param acceptClientTokens whether OAuth applications' access tokens authenticate here. A
     *        service that does not enforce their scopes must pass false, so those requests stay
     *        unauthenticated.
     */
    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider, boolean acceptClientTokens) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.acceptClientTokens = acceptClientTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtTokenProvider.parseClaims(token);
                boolean clientToken = jwtTokenProvider.isClientToken(claims);

                if (!clientToken || acceptClientTokens) {
                    UUID userId = jwtTokenProvider.getUserId(claims);
                    String role = jwtTokenProvider.getRole(claims);
                    UUID clubId = jwtTokenProvider.getClubId(claims);

                    MicommAuthenticatedUser principal =
                            new MicommAuthenticatedUser(userId, role, clubId);

                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                    if (clientToken) {
                        // Marks the request so a scope check can limit it to the token's scopes.
                        authorities.add(new SimpleGrantedAuthority(ClientScopes.CLIENT_TOKEN_AUTHORITY));
                        jwtTokenProvider.getScopes(claims)
                                .forEach(s -> authorities.add(new SimpleGrantedAuthority(ClientScopes.authority(s))));
                    }

                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, authorities);
                    // Who to name in audit logs (see MicommActor.current()).
                    authentication.setDetails(clientToken
                            ? MicommActor.apiClient(userId, jwtTokenProvider.getClientName(claims),
                                    jwtTokenProvider.getClientId(claims))
                            : MicommActor.user(userId, jwtTokenProvider.getEmail(claims)));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }

            } catch (JwtException | IllegalArgumentException ex) {
                System.out.println("JWT validation failed: " + ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}