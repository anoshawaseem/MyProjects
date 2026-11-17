package com.micomm.email.security;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.ClientScopes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limits requests made with an application's OAuth access token to that token's scopes. Users'
 * tokens and API keys pass through untouched. Anything not listed in {@link #requiredScope} is
 * refused for applications, so a new endpoint is closed to them until it is given a scope here.
 */
public class ClientScopeFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public ClientScopeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !hasAuthority(authentication, ClientScopes.CLIENT_TOKEN_AUTHORITY)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (MULTI_CHANNEL_SEND.equals(request.getRequestURI()) && "POST".equals(request.getMethod())) {
            // Which channels are sent is in the body; the service checks each channel's scope.
            // Here the token only needs to be allowed to send on at least one channel.
            if (!hasAnySendScope(authentication)) {
                refuse(response, "This application's token has no send scope ("
                        + String.join(", ", SEND_SCOPES) + ")");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }
        String scope = requiredScope(request.getMethod(), request.getRequestURI());
        if (scope == null) {
            refuse(response, "Applications cannot use " + request.getMethod() + " " + request.getRequestURI());
            return;
        }
        if (!hasAuthority(authentication, ClientScopes.authority(scope))) {
            refuse(response, "This application's token lacks the '" + scope + "' scope");
            return;
        }
        filterChain.doFilter(request, response);
    }

    static final String MULTI_CHANNEL_SEND = "/api/v1/notifications/send";

    private static final List<String> SEND_SCOPES = List.of(ClientScopes.EMAIL_SEND, ClientScopes.SMS_SEND,
            ClientScopes.WHATSAPP_SEND);

    private static boolean hasAnySendScope(Authentication authentication) {
        return SEND_SCOPES.stream().anyMatch(s -> hasAuthority(authentication, ClientScopes.authority(s)));
    }

    /**
     * Throws AccessDeniedException when the request is made with an application's token that
     * lacks the scope for one of {@code channels}. Users and API keys may send on every channel.
     */
    public static void requireSendScopes(Collection<String> channels) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !hasAuthority(authentication, ClientScopes.CLIENT_TOKEN_AUTHORITY)) {
            return;
        }
        List<String> denied = channels.stream().filter(channel -> {
            String scope = ClientScopes.sendScope(channel);
            return scope == null || !hasAuthority(authentication, ClientScopes.authority(scope));
        }).toList();
        if (!denied.isEmpty()) {
            throw new AccessDeniedException("This application's token cannot send " + String.join(", ", denied)
                    + "; it needs the scope(s) " + String.join(", ",
                            denied.stream().map(ClientScopes::sendScope).filter(s -> s != null).toList()));
        }
    }

    /** The scope an application needs for a request, or null if applications may not make it. */
    static String requiredScope(String method, String path) {
        boolean read = "GET".equals(method);
        if (path.equals("/api/v1/emails/send-bulk")) {
            return "POST".equals(method) ? ClientScopes.EMAIL_SEND : null;
        }
        if (path.startsWith("/api/v1/notifications") || path.startsWith("/api/v1/emails/logs")) {
            return read ? ClientScopes.EMAIL_READ : null;
        }
        if (path.startsWith("/api/v1/emails/templates") || path.startsWith("/api/v1/emails/global-templates")) {
            return read ? ClientScopes.TEMPLATES_READ : ClientScopes.TEMPLATES_WRITE;
        }
        return null;
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private void refuse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
    }
}
