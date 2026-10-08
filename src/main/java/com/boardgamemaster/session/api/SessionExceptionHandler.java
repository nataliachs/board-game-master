package com.boardgamemaster.session.api;

import com.boardgamemaster.common.exception.ApiError;
import com.boardgamemaster.session.domain.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {SessionController.class, TeachingController.class})
public class SessionExceptionHandler {
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ApiError("SESSION_CONFLICT", "Session data conflicts with stored configuration",
                Instant.now(), request.getRequestURI()));
    }
    @ExceptionHandler(SessionException.class)
    ResponseEntity<ApiError> session(SessionException e, HttpServletRequest request) {
        int status = switch (e.code()) {
            case SESSION_NOT_FOUND -> 404;
            case TEACHING_FLOW_NOT_FOUND, INVALID_SESSION_ACTION, SESSION_CONFLICT -> 409;
            default -> 400;
        };
        return ResponseEntity.status(status).body(new ApiError(e.code().name(), e.getMessage(), Instant.now(), request.getRequestURI()));
    }
}
