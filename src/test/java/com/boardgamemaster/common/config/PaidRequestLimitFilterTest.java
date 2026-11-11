package com.boardgamemaster.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PaidRequestLimitFilterTest {
    private static final Instant START = Instant.parse("2026-11-07T10:00:00Z");

    @Test
    void onlyAiCallingRequestsCount() {
        assertThat(PaidRequestLimitFilter.isPaid("POST", "/ui/sessions/abc/messages")).isTrue();
        assertThat(PaidRequestLimitFilter.isPaid("POST", "/api/v1/sessions/abc/messages")).isTrue();
        assertThat(PaidRequestLimitFilter.isPaid("POST", "/api/v1/sessions/abc/voice")).isTrue();
        assertThat(PaidRequestLimitFilter.isPaid("POST", "/api/v1/sessions/abc/voice/narration")).isTrue();
        assertThat(PaidRequestLimitFilter.isPaid("POST", "/api/v1/games/abc/questions")).isTrue();
        assertThat(PaidRequestLimitFilter.isPaid("GET", "/api/v1/sessions/abc/messages")).isFalse();
        assertThat(PaidRequestLimitFilter.isPaid("POST", "/ui/sessions/abc/teaching")).isFalse();
    }

    @Test
    void limitsEachClientPerHourAndResetsNextHour() {
        var clock = new MutableClock(START);
        var filter = new PaidRequestLimitFilter(2, 0, clock);
        assertThat(filter.allow("a")).isTrue();
        assertThat(filter.allow("a")).isTrue();
        assertThat(filter.allow("a")).isFalse();
        assertThat(filter.allow("b")).isTrue();
        clock.now = START.plus(Duration.ofHours(1));
        assertThat(filter.allow("a")).isTrue();
    }

    @Test
    void limitsTheWholeAppPerDay() {
        var clock = new MutableClock(START);
        var filter = new PaidRequestLimitFilter(0, 3, clock);
        assertThat(filter.allow("a")).isTrue();
        assertThat(filter.allow("b")).isTrue();
        assertThat(filter.allow("c")).isTrue();
        assertThat(filter.allow("d")).isFalse();
        clock.now = START.plus(Duration.ofDays(1));
        assertThat(filter.allow("d")).isTrue();
    }

    @Test
    void rejectsOverLimitRequestsWith429AndLeavesOthersAlone() throws Exception {
        var filter = new PaidRequestLimitFilter(1, 0, new MutableClock(START));
        assertThat(run(filter, "POST", "/ui/sessions/abc/messages").getStatus()).isEqualTo(200);
        var limited = run(filter, "POST", "/ui/sessions/abc/messages");
        assertThat(limited.getStatus()).as("the session page is sent to the styled limit page").isEqualTo(303);
        assertThat(limited.getHeader("Location")).as("a malformed session id is not passed on").isEqualTo("/ui/limit-reached");
        assertThat(limited.getContentAsString()).isEmpty();
        var session = run(filter, "POST", "/ui/sessions/0f8fad5b-d9cb-469f-a165-70867728950e/messages");
        assertThat(session.getHeader("Location")).isEqualTo("/ui/limit-reached?session=0f8fad5b-d9cb-469f-a165-70867728950e");
        assertThat(run(filter, "POST", "/api/v1/sessions/abc/messages").getStatus()).isEqualTo(429);
        assertThat(run(filter, "POST", "/api/v1/sessions/abc/messages").getContentAsString()).contains("RATE_LIMITED");
        assertThat(run(filter, "POST", "/ui/sessions/abc/teaching").getStatus()).isEqualTo(200);
    }

    @Test
    void countsDisguisedPathsThatSpringStillRoutesToPaidEndpoints() throws Exception {
        var filter = new PaidRequestLimitFilter(1, 0, new MutableClock(START));
        assertThat(run(filter, "POST", "/ui/sessions/abc/messages").getStatus()).isEqualTo(200);
        for (String path : new String[] {"/ui/sessions/abc/messages;x", "/ui/sessions/abc/%6Dessages",
                "//ui/sessions/abc/messages", "/ui;x/sessions/abc/messages"}) {
            var limited = run(filter, "POST", path);
            assertThat(limited.getStatus()).as(path).isEqualTo(303);
            assertThat(limited.getHeader("Location")).as(path).isEqualTo("/ui/limit-reached");
        }
    }

    @Test
    void doesNothingWhenLimitsAreOff() throws Exception {
        var filter = new PaidRequestLimitFilter(0, 0, new MutableClock(START));
        for (int i = 0; i < 5; i++) { assertThat(run(filter, "POST", "/ui/sessions/abc/messages").getStatus()).isEqualTo(200); }
    }

    private static MockHttpServletResponse run(PaidRequestLimitFilter filter, String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.5");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
