package com.boardgamemaster.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps requests that can call paid AI providers (questions, voice, narration) so a public
 * deployment cannot run up an unbounded bill. Limits are per client per hour and for the whole
 * application per day; 0 turns a limit off (the local default). The caps come from
 * {@link PaidRequestLimits}, so an admin can change them while the application runs.
 *
 * <p>Counters live in memory, so they reset when the app restarts and are per instance. Keep a
 * spending limit at the AI provider as the hard stop.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PaidRequestLimitFilter extends OncePerRequestFilter {
    private static final Pattern PAID = Pattern.compile(
            "^/(ui|api/v1)/sessions/[^/]+/messages$|^/api/v1/sessions/[^/]+/voice(/narration)?$|^/api/v1/games/[^/]+/questions$");

    public static final String LIMIT_PAGE = "/ui/limit-reached";
    private static final Pattern SESSION_PAGE_POST = Pattern.compile("^/ui/sessions/([^/]+)/messages$");
    private static final Pattern SESSION_ID = Pattern.compile("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$");

    private final PaidRequestLimits limits;
    private final Clock clock;
    private final Map<String, Window> clients = new ConcurrentHashMap<>();
    private Window day = new Window(-1);

    @Autowired
    public PaidRequestLimitFilter(PaidRequestLimits limits) { this(limits, Clock.systemUTC()); }

    PaidRequestLimitFilter(int perClientPerHour, int totalPerDay, Clock clock) {
        this(PaidRequestLimits.fixed(perClientPerHour, totalPerDay), clock);
    }

    PaidRequestLimitFilter(PaidRequestLimits limits, Clock clock) { this.limits = limits; this.clock = clock; }

    static boolean isPaid(String method, String path) {
        return method.equals("POST") && PAID.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isPaid(request.getMethod(), RequestPaths.normalized(request))) {
            chain.doFilter(request, response);
            return;
        }
        // Read outside the lock: this may query the database, at most once every 30 seconds.
        var caps = limits.current();
        if (!allow(request.getRemoteAddr(), caps)) { reject(request, response); return; }
        chain.doFilter(request, response);
    }

    boolean allow(String client) { return allow(client, limits.current()); }

    /** Requests are counted even while a cap is off, so a cap switched on later sees the current hour and day. */
    private synchronized boolean allow(String client, PaidRequestLimits.Limits caps) {
        int perClientPerHour = caps.perClientPerHour(), totalPerDay = caps.totalPerDay();
        long now = clock.millis();
        long hour = now / 3_600_000L, today = now / 86_400_000L;
        if (day.period != today) { day = new Window(today); clients.clear(); }
        if (totalPerDay > 0 && day.count >= totalPerDay) { return false; }
        var window = clients.compute(client == null ? "unknown" : client,
                (key, old) -> old == null || old.period != hour ? new Window(hour) : old);
        if (perClientPerHour > 0 && window.count >= perClientPerHour) { return false; }
        window.count++;
        day.count++;
        return true;
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setCharacterEncoding("UTF-8");
        var page = SESSION_PAGE_POST.matcher(RequestPaths.normalized(request));
        if (page.matches()) {
            // A form post from the session page: show the styled page. Redirecting (303) also means that
            // reloading it does not send the question again. Only a well-formed session id is passed on.
            response.setStatus(HttpServletResponse.SC_SEE_OTHER);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Location", LIMIT_PAGE + (SESSION_ID.matcher(page.group(1)).matches() ? "?session=" + page.group(1) : ""));
        } else {
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many AI requests. Try again later.\",\"path\":\""
                    + request.getRequestURI().replaceAll("[^A-Za-z0-9/_-]", "") + "\"}");
        }
    }

    private static final class Window {
        final long period; int count;
        Window(long period) { this.period = period; }
    }
}
