package com.boardgamemaster.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAccessFilterTest {
    @Test
    void protectsAdminPagesAndContentWritesButNotPlayers() {
        assertThat(AdminAccessFilter.isProtected("GET", "/admin")).isTrue();
        assertThat(AdminAccessFilter.isProtected("POST", "/admin/games")).isTrue();
        assertThat(AdminAccessFilter.isProtected("POST", "/api/v1/games")).isTrue();
        assertThat(AdminAccessFilter.isProtected("POST", "/api/v1/game-versions/x/documents")).isTrue();
        assertThat(AdminAccessFilter.isProtected("GET", "/api/v1/games")).isFalse();
        assertThat(AdminAccessFilter.isProtected("GET", "/")).isFalse();
        assertThat(AdminAccessFilter.isProtected("POST", "/ui/sessions")).isFalse();
        assertThat(AdminAccessFilter.isProtected("POST", "/api/v1/sessions/x/messages")).isFalse();
        assertThat(AdminAccessFilter.isProtected("GET", "/administrator")).isFalse();
    }

    @Test
    void ruleQuestionsStayOpenForTheHomePageChatButNothingElseUnderGames() throws Exception {
        assertThat(AdminAccessFilter.isProtected("POST", "/api/v1/games/abc/questions")).isFalse();
        assertThat(PaidRequestLimitFilter.isPaid("POST", "/api/v1/games/abc/questions")).as("still rate limited").isTrue();
        for (String path : new String[] {"/api/v1/games/abc/versions", "/api/v1/games/abc/questions/x", "/api/v1/games/questions",
                "/api/v1/games/abc/x/questions", "/api/v1/game-versions/abc/questions"}) {
            assertThat(AdminAccessFilter.isProtected("POST", path)).as(path).isTrue();
        }
        for (String method : new String[] {"PUT", "PATCH", "DELETE"}) {
            assertThat(AdminAccessFilter.isProtected(method, "/api/v1/games/abc/questions")).as(method).isTrue();
        }
        var filter = new AdminAccessFilter(true, "admin", "s3cret");
        assertThat(run(filter, "POST", "/api/v1/games/abc/questions", null).getStatus()).isEqualTo(200);
        assertThat(run(filter, "POST", "/api/v1/games/abc/questions;x", null).getStatus()).isEqualTo(200);
        assertThat(run(filter, "POST", "/api/v1/games/abc/versions", null).getStatus()).isEqualTo(401);
    }

    @Test
    void staysOpenLocallyWithoutAPassword() throws Exception {
        assertThat(run(new AdminAccessFilter(false, "admin", ""), null).getStatus()).isEqualTo(200);
    }

    @Test
    void refusesWhenRequiredButNotConfigured() throws Exception {
        assertThat(run(new AdminAccessFilter(true, "admin", ""), basic("admin", "")).getStatus()).isEqualTo(403);
    }

    @Test
    void sendsAdminPageVisitorsToTheSignInPageAndStillAcceptsBasicCredentials() throws Exception {
        var filter = new AdminAccessFilter(true, "admin", "s3cret");
        var missing = run(filter, null);
        assertThat(missing.getStatus()).isEqualTo(302);
        assertThat(missing.getRedirectedUrl()).isEqualTo("/admin/login");
        assertThat(missing.getHeader("WWW-Authenticate")).as("no browser password prompt on pages").isNull();
        assertThat(run(filter, "POST", "/admin/games", null).getRedirectedUrl()).isEqualTo("/admin/login");
        assertThat(run(filter, basic("admin", "wrong")).getRedirectedUrl()).isEqualTo("/admin/login");
        assertThat(run(filter, basic("someone", "s3cret")).getRedirectedUrl()).isEqualTo("/admin/login");
        assertThat(run(filter, basic("admin", "s3cret")).getStatus()).isEqualTo(200);
    }

    @Test
    void writeApisStillAnswer401WithABasicChallenge() throws Exception {
        var filter = new AdminAccessFilter(true, "admin", "s3cret");
        var missing = run(filter, "POST", "/api/v1/games", null);
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getHeader("WWW-Authenticate")).startsWith("Basic");
        assertThat(run(filter, "POST", "/api/v1/games", basic("admin", "wrong")).getStatus()).isEqualTo(401);
        assertThat(run(filter, "POST", "/api/v1/games", basic("admin", "s3cret")).getStatus()).isEqualTo(200);
    }

    @Test
    void theSignInAndSignOutPagesAreReachableWithoutBeingSignedIn() throws Exception {
        assertThat(AdminAccessFilter.isProtected("GET", "/admin/login")).isFalse();
        assertThat(AdminAccessFilter.isProtected("POST", "/admin/login")).isFalse();
        assertThat(AdminAccessFilter.isProtected("POST", "/admin/logout")).isFalse();
        assertThat(AdminAccessFilter.isProtected("GET", "/admin/login/x")).isTrue();
        assertThat(AdminAccessFilter.isProtected("GET", "/admin/loginx")).isTrue();
        var filter = new AdminAccessFilter(true, "admin", "s3cret");
        assertThat(run(filter, "GET", "/admin/login", null).getStatus()).isEqualTo(200);
        assertThat(run(filter, "GET", "/admin/login;x", null).getStatus()).as("path parameters are ignored, as Spring routes it").isEqualTo(200);
    }

    @Test
    void aSignedSessionCookieOpensAdminPagesUntilItExpiresAndCannotBeForged() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
        var signIn = new AdminSignIn(true, "admin", "s3cret", clock);
        var filter = new AdminAccessFilter(signIn);
        String token = signIn.issue();
        assertThat(withCookie(filter, "GET", "/admin", token).getStatus()).isEqualTo(200);
        assertThat(withCookie(filter, "POST", "/admin/games", token).getStatus()).isEqualTo(200);
        assertThat(withCookie(filter, "POST", "/api/v1/games", token).getStatus()).as("the cookie also authorizes the write APIs it is sent to").isEqualTo(200);

        String expires = token.substring(0, token.indexOf('.'));
        String later = Long.toString(Long.parseLong(expires) + 1);
        for (String forged : new String[] {later + token.substring(token.indexOf('.')), expires + ".", expires, "." + token,
                token + "x", "", "not-a-token", "9".repeat(300)}) {
            assertThat(withCookie(filter, "GET", "/admin", forged).getRedirectedUrl()).as(forged).isEqualTo("/admin/login");
        }
        assertThat(new AdminSignIn(true, "admin", "changed", clock).valid(token)).as("changing the password signs everyone out").isFalse();
        assertThat(new AdminSignIn(true, "other", "s3cret", clock).valid(token)).isFalse();

        clock.now = clock.now.plus(AdminSignIn.SESSION).minusSeconds(1);
        assertThat(withCookie(filter, "GET", "/admin", token).getStatus()).isEqualTo(200);
        clock.now = clock.now.plusSeconds(1);
        assertThat(withCookie(filter, "GET", "/admin", token).getRedirectedUrl()).as("expired").isEqualTo("/admin/login");
    }

    @Test
    void theSessionCookieIsHttpOnlyStrictAndLimitedToTheAdminArea() {
        var signIn = new AdminSignIn(true, "admin", "s3cret");
        assertThat(signIn.cookie("value", true)).contains("bgm_admin=value", "Path=/admin", "HttpOnly", "Secure", "SameSite=Strict", "Max-Age=28800");
        assertThat(signIn.cookie("value", false)).doesNotContain("Secure");
        assertThat(signIn.clearingCookie(true)).contains("bgm_admin=;", "Max-Age=0", "Path=/admin");
    }

    @Test
    void repeatedWrongPasswordsBlockThatClientForFifteenMinutes() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
        var signIn = new AdminSignIn(true, "admin", "s3cret", clock);
        var filter = new AdminAccessFilter(signIn);
        for (int i = 0; i < AdminSignIn.MAX_FAILURES; i++) {
            assertThat(signIn.blocked("127.0.0.1")).isFalse();
            assertThat(run(filter, "POST", "/api/v1/games", basic("admin", "guess" + i)).getStatus()).isEqualTo(401);
        }
        assertThat(signIn.blocked("127.0.0.1")).isTrue();
        assertThat(run(filter, "POST", "/api/v1/games", basic("admin", "s3cret")).getStatus()).as("even the right password waits").isEqualTo(401);
        assertThat(signIn.blocked("198.51.100.7")).as("other clients are unaffected").isFalse();
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(15));
        assertThat(signIn.blocked("127.0.0.1")).isFalse();
        assertThat(run(filter, "POST", "/api/v1/games", basic("admin", "s3cret")).getStatus()).isEqualTo(200);
    }

    @Test
    void withoutAPasswordNothingMatchesAndNoSessionCanBeIssued() {
        var open = new AdminSignIn(false, "admin", "");
        assertThat(open.configured()).isFalse();
        assertThat(open.matches("admin", "")).isFalse();
        assertThat(open.valid("1.x")).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(open::issue).isInstanceOf(IllegalStateException.class);
    }

    private static MockHttpServletResponse withCookie(AdminAccessFilter filter, String method, String path, String token) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setCookies(new jakarta.servlet.http.Cookie(AdminSignIn.COOKIE, token));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static final class MutableClock extends java.time.Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneOffset getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void protectsDisguisedPathsThatSpringStillRoutesToProtectedControllers() throws Exception {
        var filter = new AdminAccessFilter(true, "admin", "s3cret");
        for (String path : new String[] {"/admin;x", "/admin;jsessionid=1/games", "/%61dmin", "//admin", "/admin/%67ames"}) {
            assertThat(run(filter, "GET", path, null).getRedirectedUrl()).as(path).isEqualTo("/admin/login");
        }
        for (String path : new String[] {"/api/v1/games;x", "/api/v1/%67ames", "//api/v1/game-versions/x/documents"}) {
            assertThat(run(filter, "POST", path, null).getStatus()).as(path).isEqualTo(401);
        }
        assertThat(run(filter, "GET", "/api/v1/games;x", null).getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse run(AdminAccessFilter filter, String authorization) throws Exception {
        return run(filter, "GET", "/admin", authorization);
    }

    private static MockHttpServletResponse run(AdminAccessFilter filter, String method, String path, String authorization)
            throws Exception {
        var request = new MockHttpServletRequest(method, path);
        if (authorization != null) { request.addHeader("Authorization", authorization); }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
