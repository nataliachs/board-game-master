package com.boardgamemaster.common.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The admin credentials and the browser sign-in built on them.
 *
 * <p>The admin signs in on a page and receives a cookie holding an expiry time and an HMAC of it. The
 * key is derived from the admin password, so nothing is stored on the server: every instance accepts
 * the cookie, a restart or deployment keeps the admin signed in, and changing the password signs
 * everyone out. Signing out only removes the cookie from that browser; a copied cookie stays valid
 * until it expires. HTTP Basic credentials are still accepted for scripts and API clients.
 *
 * <p>Wrong passwords are counted per client address: after {@value #MAX_FAILURES} failures within
 * 15 minutes, further attempts from that address are refused until the window ends. Counters live in
 * memory, per instance.
 */
@Component
public class AdminSignIn {
    public static final String COOKIE = "bgm_admin";
    public static final Duration SESSION = Duration.ofHours(8);
    static final int MAX_FAILURES = 10;
    private static final long FAILURE_WINDOW_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final boolean required;
    private final byte[] expectedUser;
    private final byte[] expectedPassword;
    private final byte[] sessionKey;
    private final Clock clock;
    private final Map<String, long[]> failures = new HashMap<>(); // client -> {window start, count}

    @Autowired
    public AdminSignIn(@Value("${boardgame.admin.required:false}") boolean required,
            @Value("${boardgame.admin.username:admin}") String username,
            @Value("${boardgame.admin.password:}") String password) {
        this(required, username, password, Clock.systemUTC());
    }

    AdminSignIn(boolean required, String username, String password, Clock clock) {
        this.required = required;
        this.expectedUser = username.getBytes(StandardCharsets.UTF_8);
        this.expectedPassword = password.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.sessionKey = expectedPassword.length == 0 ? new byte[0]
                : hmac(expectedPassword, ("board-game-master admin session v1:" + username).getBytes(StandardCharsets.UTF_8));
    }

    /** True when a password is set, so the admin area asks for it. */
    public boolean configured() { return expectedPassword.length > 0; }

    /** True when the admin area must refuse everyone while no password is set (the prod profile). */
    public boolean required() { return required; }

    public boolean matches(String username, String password) {
        if (!configured() || username == null || password == null) { return false; }
        boolean user = MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8), expectedUser);
        boolean pass = MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), expectedPassword);
        return user & pass;
    }

    boolean basicAuthorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) { return false; }
        String decoded;
        try { decoded = new String(Base64.getDecoder().decode(header.substring(6).strip()), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return false; }
        int colon = decoded.indexOf(':');
        return colon >= 0 && matches(decoded.substring(0, colon), decoded.substring(colon + 1));
    }

    /** A new signed session value; call only after {@link #matches} succeeded. */
    public String issue() {
        if (!configured()) { throw new IllegalStateException("Admin sign-in is not configured"); }
        String expires = Long.toString(clock.millis() + SESSION.toMillis());
        return expires + "." + sign(expires);
    }

    boolean valid(String token) {
        if (!configured() || token == null || token.length() > 200) { return false; }
        int dot = token.indexOf('.');
        if (dot <= 0) { return false; }
        String expires = token.substring(0, dot);
        // Compare the signature first, in constant time, before trusting the expiry text.
        if (!MessageDigest.isEqual(sign(expires).getBytes(StandardCharsets.US_ASCII),
                token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) { return false; }
        try { return Long.parseLong(expires) > clock.millis(); }
        catch (NumberFormatException e) { return false; }
    }

    public boolean signedIn(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) { return false; }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName()) && valid(cookie.getValue())) { return true; }
        }
        return false;
    }

    /** The Set-Cookie value that signs this browser in; sent only over HTTPS when the request was. */
    public String cookie(String token, boolean secure) {
        return ResponseCookie.from(COOKIE, token).path("/admin").httpOnly(true).secure(secure).sameSite("Strict")
                .maxAge(SESSION).build().toString();
    }

    public String clearingCookie(boolean secure) {
        return ResponseCookie.from(COOKIE, "").path("/admin").httpOnly(true).secure(secure).sameSite("Strict")
                .maxAge(0).build().toString();
    }

    /** True when this client has used up its wrong-password attempts for the current window. */
    public synchronized boolean blocked(String client) {
        var entry = failures.get(key(client));
        return entry != null && clock.millis() - entry[0] < FAILURE_WINDOW_MILLIS && entry[1] >= MAX_FAILURES;
    }

    public synchronized void failed(String client) {
        long now = clock.millis();
        if (failures.size() >= MAX_TRACKED_CLIENTS) { failures.values().removeIf(e -> now - e[0] >= FAILURE_WINDOW_MILLIS); }
        if (failures.size() >= MAX_TRACKED_CLIENTS) { failures.clear(); } // Bounded memory beats a perfect count.
        var entry = failures.get(key(client));
        if (entry == null || now - entry[0] >= FAILURE_WINDOW_MILLIS) { failures.put(key(client), new long[] {now, 1}); }
        else { entry[1]++; }
    }

    public synchronized void succeeded(String client) { failures.remove(key(client)); }

    private static String key(String client) { return client == null ? "unknown" : client; }

    private String sign(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(sessionKey, text.getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("HMAC-SHA256 is unavailable", e); }
    }
}
