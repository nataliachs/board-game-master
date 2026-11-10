package com.boardgamemaster.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
    void asksForAndChecksTheConfiguredPassword() throws Exception {
        var filter = new AdminAccessFilter(true, "admin", "s3cret");
        var missing = run(filter, null);
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getHeader("WWW-Authenticate")).startsWith("Basic");
        assertThat(run(filter, basic("admin", "wrong")).getStatus()).isEqualTo(401);
        assertThat(run(filter, basic("someone", "s3cret")).getStatus()).isEqualTo(401);
        assertThat(run(filter, basic("admin", "s3cret")).getStatus()).isEqualTo(200);
    }

    @Test
    void protectsDisguisedPathsThatSpringStillRoutesToProtectedControllers() throws Exception {
        var filter = new AdminAccessFilter(true, "admin", "s3cret");
        for (String path : new String[] {"/admin;x", "/admin;jsessionid=1/games", "/%61dmin", "//admin", "/admin/%67ames"}) {
            assertThat(run(filter, "GET", path, null).getStatus()).as(path).isEqualTo(401);
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
