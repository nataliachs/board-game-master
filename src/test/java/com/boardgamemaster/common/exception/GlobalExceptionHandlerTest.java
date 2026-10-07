package com.boardgamemaster.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.boardgamemaster.common.config.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter()).build();
    }

    @Test
    void malformedJsonIsSafeAndConsistent() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/probe"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void validationFailureIsBadRequest() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unsupportedMethodAndMediaTypeKeepTheirStatus() throws Exception {
        mvc.perform(get("/probe")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().exists("Allow"));
        mvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("value"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unexpectedFailureHidesDetailsAndClearsRequestContext() throws Exception {
        mvc.perform(get("/failure").header("X-Request-ID", "untrusted"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(header().string("X-Request-ID", org.hamcrest.Matchers.not("untrusted")));
        assertThat(MDC.get("requestId")).isNull();
    }

    @RestController
    static class ProbeController {
        @PostMapping(value = "/probe", consumes = MediaType.APPLICATION_JSON_VALUE)
        Payload accept(@Valid @RequestBody Payload payload) { return payload; }

        @GetMapping("/failure")
        void fail() { throw new IllegalStateException("secret-db-password"); }
    }

    record Payload(@NotBlank String value) { }
}
