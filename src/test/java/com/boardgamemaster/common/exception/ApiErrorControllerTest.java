package com.boardgamemaster.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiErrorControllerTest {
    private final ApiErrorController controller = new ApiErrorController();

    @Test
    void servletErrorPreservesOriginalStatusAndPathWithoutExceptionDetails() {
        var request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/missing");
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "private diagnostic");
        var response = controller.error(request);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo("/missing");
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).doesNotContain("private diagnostic");
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void directErrorRequestUsesSafeFallback() {
        var response = controller.error(new MockHttpServletRequest("GET", "/error"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().path()).isEqualTo("/error");
    }
}
