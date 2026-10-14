package hu.auxin.ibkrfacade.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Optional bearer token authentication with two tokens:
 * <ul>
 *     <li>read token: the read-only endpoints (GET, and POST /price)</li>
 *     <li>write token: every endpoint, including placing (POST /order) and cancelling (DELETE /orders/{id}) orders</li>
 * </ul>
 * The health endpoint (and optionally the API docs) are public. Enable it with IBKR_SECURITY_ENABLED=true and
 * set IBKR_SECURITY_READ_TOKEN / IBKR_SECURITY_WRITE_TOKEN.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final List<String> PUBLIC_PATHS = List.of("/actuator/health", "/actuator/info");
    private static final List<String> DOC_PATHS = List.of("/v3/api-docs", "/swagger-ui");
    private static final List<String> READ_ONLY_POST_PATHS = List.of("/price");

    private final boolean enabled;
    private final boolean publicDocs;
    private final byte[] readTokenHash;
    private final byte[] writeTokenHash;

    public ApiTokenFilter(@Value("${ibkr.security.enabled:false}") boolean enabled,
                          @Value("${ibkr.security.read-token:}") String readToken,
                          @Value("${ibkr.security.write-token:}") String writeToken,
                          @Value("${ibkr.security.public-docs:true}") boolean publicDocs) {
        this.enabled = enabled;
        this.publicDocs = publicDocs;
        if (enabled) {
            if (!StringUtils.hasText(writeToken)) {
                throw new IllegalStateException("ibkr.security.write-token must be set when the API token "
                        + "authentication is enabled");
            }
            if (writeToken.equals(readToken)) {
                throw new IllegalStateException("ibkr.security.read-token and ibkr.security.write-token must differ");
            }
            if (writeToken.length() < 16 || (StringUtils.hasText(readToken) && readToken.length() < 16)) {
                throw new IllegalStateException("API tokens must be at least 16 characters long");
            }
            log.info("API token authentication is enabled (read token: {})", StringUtils.hasText(readToken) ? "set" : "not set");
        } else {
            log.warn("API token authentication is disabled, the REST API must only be reachable from a trusted network");
        }
        this.readTokenHash = StringUtils.hasText(readToken) ? sha256(readToken) : null;
        this.writeTokenHash = StringUtils.hasText(writeToken) ? sha256(writeToken) : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        Scope scope = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (scope == null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid API token");
            return;
        }
        if (scope == Scope.READ && requiresWrite(request.getMethod(), path)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
            reject(response, HttpStatus.FORBIDDEN, "INSUFFICIENT_SCOPE", "This operation requires the write token");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Safe by default: every request except GET / HEAD requires the write token (eg. POST /order,
     * DELETE /orders/{id}), the only exception is the read-only POST /price. The path is compared literally,
     * so an encoded or otherwise unusual form of /price requires the write token as well.
     */
    static boolean requiresWrite(String method, String path) {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return false;
        }
        return !("POST".equals(method) && READ_ONLY_POST_PATHS.contains(path));
    }

    private boolean isPublic(String path) {
        if (PUBLIC_PATHS.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))) {
            return true;
        }
        return publicDocs && (DOC_PATHS.stream().anyMatch(path::startsWith) || path.equals("/swagger-ui.html"));
    }

    private Scope authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        byte[] token = sha256(authorization.substring(BEARER.length()).trim());
        if (writeTokenHash != null && MessageDigest.isEqual(token, writeTokenHash)) {
            return Scope.WRITE;
        }
        if (readTokenHash != null && MessageDigest.isEqual(token, readTokenHash)) {
            return Scope.READ;
        }
        return null;
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status.value() + ",\"title\":\"" + status.getReasonPhrase()
                + "\",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private enum Scope {
        READ, WRITE
    }
}
