package com.boardgamemaster.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Password-protects the admin pages and every catalog/content write API with HTTP Basic auth.
 * Players' pages, sessions and read-only catalog requests stay open.
 *
 * <p>Locally (no {@code ADMIN_PASSWORD}) everything stays open, as before. When
 * {@code boardgame.admin.required=true} (the prod profile) and no password is configured,
 * protected requests are refused rather than left open.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminAccessFilter extends OncePerRequestFilter {
    private final boolean required;
    private final byte[] expectedUser;
    private final byte[] expectedPassword;

    public AdminAccessFilter(@Value("${boardgame.admin.required:false}") boolean required,
            @Value("${boardgame.admin.username:admin}") String username,
            @Value("${boardgame.admin.password:}") String password) {
        this.required = required;
        this.expectedUser = username.getBytes(StandardCharsets.UTF_8);
        this.expectedPassword = password.getBytes(StandardCharsets.UTF_8);
    }

    static boolean isProtected(String method, String path) {
        if (path.equals("/admin") || path.startsWith("/admin/")) { return true; }
        boolean write = !(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS"));
        return write && (path.equals("/api/v1/games") || path.startsWith("/api/v1/games/")
                || path.startsWith("/api/v1/game-versions/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isProtected(request.getMethod(), RequestPaths.normalized(request))) { chain.doFilter(request, response); return; }
        if (expectedPassword.length == 0) {
            if (required) { response.sendError(HttpServletResponse.SC_FORBIDDEN, "Admin access is not configured"); return; }
            chain.doFilter(request, response);
            return;
        }
        if (authorized(request.getHeader("Authorization"))) { chain.doFilter(request, response); return; }
        response.setHeader("WWW-Authenticate", "Basic realm=\"Board Game Master admin\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) { return false; }
        String decoded;
        try { decoded = new String(Base64.getDecoder().decode(header.substring(6).strip()), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return false; }
        int colon = decoded.indexOf(':');
        if (colon < 0) { return false; }
        boolean user = MessageDigest.isEqual(decoded.substring(0, colon).getBytes(StandardCharsets.UTF_8), expectedUser);
        boolean pass = MessageDigest.isEqual(decoded.substring(colon + 1).getBytes(StandardCharsets.UTF_8), expectedPassword);
        return user & pass;
    }
}
