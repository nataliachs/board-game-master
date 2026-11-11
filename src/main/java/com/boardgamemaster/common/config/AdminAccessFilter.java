package com.boardgamemaster.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Password-protects the admin pages and every catalog/content write API. Players' pages, sessions,
 * read-only catalog requests and rule questions stay open.
 *
 * <p>Admin pages are opened by signing in at {@code /admin/login}, which sets the cookie checked here
 * (see {@link AdminSignIn}); a visitor who is not signed in is sent to that page. HTTP Basic
 * credentials are accepted everywhere, and the write APIs answer 401 with a Basic challenge as before.
 *
 * <p>Locally (no {@code ADMIN_PASSWORD}) everything stays open, as before. When
 * {@code boardgame.admin.required=true} (the prod profile) and no password is configured,
 * protected requests are refused rather than left open.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminAccessFilter extends OncePerRequestFilter {
    public static final String LOGIN_PATH = "/admin/login";
    public static final String LOGOUT_PATH = "/admin/logout";
    private static final java.util.regex.Pattern RULE_QUESTION = java.util.regex.Pattern.compile("/api/v1/games/[^/]+/questions");
    private final AdminSignIn signIn;

    @Autowired
    public AdminAccessFilter(AdminSignIn signIn) { this.signIn = signIn; }

    AdminAccessFilter(boolean required, String username, String password) {
        this(new AdminSignIn(required, username, password));
    }

    static boolean isAdminPage(String path) { return path.equals("/admin") || path.startsWith("/admin/"); }

    static boolean isProtected(String method, String path) {
        // The sign-in page must be reachable without being signed in; it checks the password itself.
        if (path.equals(LOGIN_PATH) || path.equals(LOGOUT_PATH)) { return false; }
        if (isAdminPage(path)) { return true; }
        boolean write = !(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS"));
        // Asking a rule question is a POST but changes nothing: the home page chat sends it for
        // any visitor. PaidRequestLimitFilter still caps it like every other AI request.
        if (method.equals("POST") && RULE_QUESTION.matcher(path).matches()) { return false; }
        return write && (path.equals("/api/v1/games") || path.startsWith("/api/v1/games/")
                || path.startsWith("/api/v1/game-versions/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = RequestPaths.normalized(request);
        if (!isProtected(request.getMethod(), path)) { chain.doFilter(request, response); return; }
        if (!signIn.configured()) {
            if (signIn.required()) { response.sendError(HttpServletResponse.SC_FORBIDDEN, "Admin access is not configured"); return; }
            chain.doFilter(request, response);
            return;
        }
        if (signIn.signedIn(request)) { chain.doFilter(request, response); return; }
        String header = request.getHeader("Authorization");
        String client = request.getRemoteAddr();
        if (header != null && !signIn.blocked(client)) {
            if (signIn.basicAuthorized(header)) { signIn.succeeded(client); chain.doFilter(request, response); return; }
            signIn.failed(client);
        }
        if (isAdminPage(path)) {
            // A browser is sent to the sign-in page instead of being shown the browser's own password prompt.
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(LOGIN_PATH);
            return;
        }
        response.setHeader("WWW-Authenticate", "Basic realm=\"Board Game Master admin\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
