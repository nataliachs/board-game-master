package com.boardgamemaster.common.config;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The caps used by {@link PaidRequestLimitFilter}. They start from configuration
 * ({@code PAID_REQUESTS_PER_CLIENT_PER_HOUR}, {@code PAID_REQUESTS_PER_DAY}); an admin can replace them
 * on the admin page, which stores them in {@code app_settings} so they survive restarts and deployments.
 * Stored values are re-read at most every 30 seconds, so every running instance follows a change.
 * If the database cannot be read, the last known values stay in force.
 */
@Component
public class PaidRequestLimits {
    public static final int MAXIMUM = 100_000;
    static final String PER_CLIENT_KEY = "paid-requests.per-client-per-hour";
    static final String PER_DAY_KEY = "paid-requests.total-per-day";
    private static final long REFRESH_MILLIS = 30_000;
    private static final Logger LOG = LoggerFactory.getLogger(PaidRequestLimits.class);

    /** 0 turns a cap off. {@code custom} is false while the configured defaults apply. */
    public record Limits(int perClientPerHour, int totalPerDay, boolean custom) { }

    private final Limits configured;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Limits current;
    private volatile long loadedAt = Long.MIN_VALUE;

    @Autowired
    public PaidRequestLimits(@Value("${boardgame.paid-requests.per-client-per-hour:0}") int perClientPerHour,
            @Value("${boardgame.paid-requests.total-per-day:0}") int totalPerDay, ObjectProvider<JdbcTemplate> jdbc) {
        this(perClientPerHour, totalPerDay, jdbc.getIfAvailable(), Clock.systemUTC());
    }

    PaidRequestLimits(int perClientPerHour, int totalPerDay, JdbcTemplate jdbc, Clock clock) {
        this.configured = new Limits(Math.max(0, perClientPerHour), Math.max(0, totalPerDay), false);
        this.current = configured; this.jdbc = jdbc; this.clock = clock;
    }

    /** Fixed caps with no database, for tests and for running without one. */
    static PaidRequestLimits fixed(int perClientPerHour, int totalPerDay) {
        return new PaidRequestLimits(perClientPerHour, totalPerDay, null, Clock.systemUTC());
    }

    public Limits configured() { return configured; }

    public Limits current() {
        long now = clock.millis();
        if (jdbc != null && (loadedAt == Long.MIN_VALUE || now - loadedAt >= REFRESH_MILLIS)) {
            loadedAt = now; // Set first: a failing database is retried after the interval, not on every request.
            try { current = load(); }
            catch (RuntimeException e) { LOG.warn("Paid request limits could not be read; keeping the current ones: {}", e.getClass().getName()); }
        }
        return current;
    }

    /** Stores both caps and applies them at once on this instance. */
    public Limits update(int perClientPerHour, int totalPerDay) {
        if (perClientPerHour < 0 || perClientPerHour > MAXIMUM || totalPerDay < 0 || totalPerDay > MAXIMUM) {
            throw new IllegalArgumentException("Each limit must be a whole number from 0 to " + MAXIMUM + ". Use 0 for no limit.");
        }
        if (jdbc == null) { throw new IllegalStateException("Limits cannot be saved without a database"); }
        store(PER_CLIENT_KEY, perClientPerHour);
        store(PER_DAY_KEY, totalPerDay);
        loadedAt = clock.millis();
        current = new Limits(perClientPerHour, totalPerDay, true);
        LOG.info("Paid request limits changed: perClientPerHour={}, totalPerDay={}", perClientPerHour, totalPerDay);
        return current;
    }

    /** Removes the stored caps so the configured defaults apply again. */
    public Limits reset() {
        if (jdbc == null) { throw new IllegalStateException("Limits cannot be saved without a database"); }
        jdbc.update("DELETE FROM app_settings WHERE setting_key IN (?, ?)", PER_CLIENT_KEY, PER_DAY_KEY);
        loadedAt = clock.millis();
        current = configured;
        LOG.info("Paid request limits reset to the configured defaults");
        return current;
    }

    private void store(String key, int value) {
        jdbc.update("INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?) "
                + "ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = now()",
                key, Integer.toString(value));
    }

    private Limits load() {
        Integer perClient = null, perDay = null;
        for (var row : jdbc.queryForList("SELECT setting_key, setting_value FROM app_settings WHERE setting_key IN (?, ?)",
                PER_CLIENT_KEY, PER_DAY_KEY)) {
            Integer value = parse((String) row.get("setting_value"));
            if (PER_CLIENT_KEY.equals(row.get("setting_key"))) { perClient = value; } else { perDay = value; }
        }
        if (perClient == null && perDay == null) { return configured; }
        return new Limits(perClient == null ? configured.perClientPerHour() : perClient,
                perDay == null ? configured.totalPerDay() : perDay, true);
    }

    private static Integer parse(String value) {
        try {
            int n = Integer.parseInt(value.strip());
            return n < 0 || n > MAXIMUM ? null : n;
        } catch (RuntimeException e) { return null; }
    }
}
