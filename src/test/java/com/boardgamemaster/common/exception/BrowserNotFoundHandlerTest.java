package com.boardgamemaster.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

class BrowserNotFoundHandlerTest {
    private final BrowserNotFoundHandler handler = new BrowserNotFoundHandler();

    @Test
    void browsersAskingForAPageGetTheFriendlyPage() {
        var result = handler.notFound(request("/no-such-page", "text/html,application/xhtml+xml,*/*;q=0.8"));
        assertThat(result).isInstanceOf(ModelAndView.class);
        var view = (ModelAndView) result;
        assertThat(view.getViewName()).isEqualTo("error");
        assertThat(view.getStatus().value()).isEqualTo(404);
        assertThat(view.getModel().get("error")).asString().contains("doesn't exist");
    }

    @Test
    void apiPathsAndNonBrowserClientsKeepJson() {
        for (var request : new MockHttpServletRequest[] {request("/api/v1/nope", "text/html"),
                request("/no-such-page", "application/json"), request("/css/missing.css", "text/css,*/*;q=0.1"),
                request("/no-such-page", null)}) {
            var result = handler.notFound(request);
            assertThat(result).as(request.getRequestURI()).isInstanceOf(ResponseEntity.class);
            var response = (ResponseEntity<?>) result;
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(((ApiError) response.getBody()).code()).isEqualTo("NOT_FOUND");
        }
    }

    private static MockHttpServletRequest request(String path, String accept) {
        var request = new MockHttpServletRequest("GET", path);
        if (accept != null) { request.addHeader("Accept", accept); }
        return request;
    }
}
