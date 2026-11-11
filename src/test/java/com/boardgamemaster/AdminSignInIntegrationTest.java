package com.boardgamemaster;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The admin area with a password set, as on the deployed site: sign-in page, cookie, sign-out and question limits. */
@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "boardgame.admin.required=true", "boardgame.admin.username=keeper", "boardgame.admin.password=fixture-admin-pass",
        "boardgame.paid-requests.per-client-per-hour=1"})
class AdminSignInIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;

    @Test
    void visitorsAreSentToTheSignInPageAndACorrectPasswordOpensTheAdminArea() throws Exception {
        var anonymous = send("GET", "/admin", null, null, null);
        assertThat(anonymous.statusCode()).isEqualTo(302);
        assertThat(location(anonymous)).isEqualTo("/admin/login");
        assertThat(anonymous.headers().firstValue("WWW-Authenticate")).as("no browser password prompt").isEmpty();
        assertThat(location(send("POST", "/admin/limits", "perClientPerHour=0&totalPerDay=0", null, null))).isEqualTo("/admin/login");

        var page = send("GET", "/admin/login", null, null, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Sign in", "name=\"username\"", "type=\"password\"", "autocomplete=\"current-password\"",
                "action=\"/admin/login\"", "/css/demo.css").doesNotContain("style=", "<script");
        assertThat(page.headers().firstValue("Content-Security-Policy").orElse("")).contains("form-action 'self'");
        assertThat(page.headers().firstValue("Cache-Control").orElse("")).contains("no-store");

        var wrong = send("POST", "/admin/login", "username=keeper&password=nope", null, null);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).contains("username or password is not right", "value=\"keeper\"").doesNotContain("nope");
        assertThat(wrong.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(send("POST", "/admin/login", "username=admin&password=fixture-admin-pass", null, null).statusCode()).isEqualTo(401);

        var signedIn = send("POST", "/admin/login", "username=keeper&password=fixture-admin-pass", null, null);
        assertThat(signedIn.statusCode()).isEqualTo(302);
        assertThat(location(signedIn)).isEqualTo("/admin");
        String setCookie = signedIn.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).contains("bgm_admin=", "Path=/admin", "HttpOnly", "SameSite=Strict").doesNotContain("fixture-admin-pass");
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        var admin = send("GET", "/admin", null, cookie, null);
        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(admin.body()).contains("Your game library", "Sign out", "Question limits").doesNotContain("has no sign-in");
        assertThat(location(send("GET", "/admin/login", null, cookie, null))).as("already signed in").isEqualTo("/admin");
        assertThat(send("GET", "/admin", null, cookie + "x", null).statusCode()).as("a changed cookie is refused").isEqualTo(302);

        var signedOut = send("POST", "/admin/logout", "", cookie, null);
        assertThat(location(signedOut)).isEqualTo("/admin/login");
        assertThat(signedOut.headers().firstValue("Set-Cookie").orElseThrow()).contains("bgm_admin=;", "Max-Age=0");
    }

    @Test
    void crossSiteSignInPostsAreRefusedAndBasicCredentialsStillWork() throws Exception {
        assertThat(send("POST", "/admin/login", "username=keeper&password=fixture-admin-pass", null, "cross-site").statusCode()).isEqualTo(403);
        String basic = "Basic " + Base64.getEncoder().encodeToString("keeper:fixture-admin-pass".getBytes(StandardCharsets.UTF_8));
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin"))
                    .header("Authorization", basic).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        var api = send("POST", "/api/v1/games", "{}", null, null);
        assertThat(api.statusCode()).isEqualTo(401);
        assertThat(api.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Basic");
    }

    @Test
    void aQuestionOverTheLimitLeadsToTheStyledBreakPage() throws Exception {
        String session = "0f8fad5b-d9cb-469f-a165-70867728950e";
        // The first question is counted and reaches the application (which has no such session); the second is over the limit of 1.
        send("POST", "/ui/sessions/" + session + "/messages", "question=One", null, null);
        var limited = send("POST", "/ui/sessions/" + session + "/messages", "question=Two", null, null);
        assertThat(limited.statusCode()).isEqualTo(303);
        assertThat(location(limited)).isEqualTo("/ui/limit-reached?session=" + session);

        var page = send("GET", "/ui/limit-reached?session=" + session, null, null, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("The Game Master needs a short break.", "Lessons and setup steps still work",
                "href=\"/sessions/" + session + "\"", "Back to your game", "/css/demo.css", "site-header").doesNotContain("style=", "<script");
        assertThat(page.headers().firstValue("Content-Security-Policy")).isPresent();
        var noSession = send("GET", "/ui/limit-reached?session=%22%3E%3Cb%3E", null, null, null);
        assertThat(noSession.body()).contains("Return to game selection").doesNotContain("Back to your game", "<b>");

        var api = send("POST", "/api/v1/sessions/" + session + "/messages", "{\"question\":\"Three\"}", null, null);
        assertThat(api.statusCode()).isEqualTo(429);
        assertThat(api.body()).contains("RATE_LIMITED");
    }

    private static String location(HttpResponse<String> response) {
        return URI.create(response.headers().firstValue("Location").orElseThrow()).getPath()
                + (URI.create(response.headers().firstValue("Location").orElseThrow()).getQuery() == null ? ""
                : "?" + URI.create(response.headers().firstValue("Location").orElseThrow()).getQuery());
    }

    private HttpResponse<String> send(String method, String path, String body, String cookie, String fetchSite) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (cookie != null) { request.header("Cookie", cookie); }
            if (fetchSite != null) { request.header("Sec-Fetch-Site", fetchSite); }
            if (method.equals("GET")) { request.GET(); }
            else {
                request.header("Content-Type", body != null && body.startsWith("{") ? "application/json" : "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
