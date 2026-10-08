package com.boardgamemaster.ui.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local demo has no authentication: avoid cross-origin browser form writes and cached private pages. */
@Component
public class DemoPageHeaders extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/") || path.startsWith("/sessions/") || path.startsWith("/ui/")
                || (path.startsWith("/api/v1/sessions/") && path.contains("/voice"))) {
            response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "same-origin");
            response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
            if (!request.getMethod().equals("GET") && "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) { response.sendError(403); return; }
        }
        chain.doFilter(request, response);
    }
}
