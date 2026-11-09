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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps requests that can call paid AI providers (questions, voice, narration) so a public
 * deployment cannot run up an unbounded bill. Limits are per client per hour and for the whole
 * application per day; 0 turns a limit off (the local default).
 *
 * <p>Counters live in memory, so they reset when the app restarts and are per instance. Keep a
 * spending limit at the AI provider as the hard stop.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PaidRequestLimitFilter extends OncePerRequestFilter {
    private static final Pattern PAID = Pattern.compile(
            "^/(ui|api/v1)/sessions/[^/]+/messages$|^/api/v1/sessions/[^/]+/voice(/narration)?$|^/api/v1/games/[^/]+/questions$");

    private final int perClientPerHour;
    private final int totalPerDay;
    private final Clock clock;
    private final Map<String, Window> clients = new ConcurrentHashMap<>();
    private Window day = new Window(-1);

    @Autowired
    public PaidRequestLimitFilter(@Value("${boardgame.paid-requests.per-client-per-hour:0}") int perClientPerHour,
            @Value("${boardgame.paid-requests.total-per-day:0}") int totalPerDay) {
        this(perClientPerHour, totalPerDay, Clock.systemUTC());
    }

    PaidRequestLimitFilter(int perClientPerHour, int totalPerDay, Clock clock) {
        this.perClientPerHour = perClientPerHour; this.totalPerDay = totalPerDay; this.clock = clock;
    }

    static boolean isPaid(String method, String path) {
        return method.equals("POST") && PAID.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ((perClientPerHour <= 0 && totalPerDay <= 0) || !isPaid(request.getMethod(), RequestPaths.normalized(request))) {
            chain.doFilter(request, response);
            return;
        }
        if (!allow(request.getRemoteAddr())) { reject(request, response); return; }
        chain.doFilter(request, response);
    }

    synchronized boolean allow(String client) {
        long now = clock.millis();
        long hour = now / 3_600_000L, today = now / 86_400_000L;
        if (day.period != today) { day = new Window(today); clients.clear(); }
        if (totalPerDay > 0 && day.count >= totalPerDay) { return false; }
        if (perClientPerHour > 0) {
            var window = clients.compute(client == null ? "unknown" : client,
                    (key, old) -> old == null || old.period != hour ? new Window(hour) : old);
            if (window.count >= perClientPerHour) { return false; }
            window.count++;
        }
        day.count++;
        return true;
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setCharacterEncoding("UTF-8");
        if (RequestPaths.normalized(request).startsWith("/ui/")) {
            response.setContentType("text/html");
            response.getWriter().write("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>Please wait</title></head>"
                    + "<body><h1>The Game Master needs a short break.</h1><p>Too many questions were asked recently. "
                    + "Lessons and setup still work. Go back and try your question again later.</p></body></html>");
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
