package com.boardgamemaster.rules.api;

import com.boardgamemaster.common.exception.ApiError;
import com.boardgamemaster.rules.domain.RuleImportException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = RuleDocumentController.class)
public class RuleExceptionHandler {
    @ExceptionHandler(RuleImportException.class)
    ResponseEntity<ApiError> ruleError(RuleImportException exception, HttpServletRequest request) {
        int status = switch (exception.code()) {
            case GAME_VERSION_NOT_FOUND, DOCUMENT_NOT_FOUND -> 404;
            case UNSUPPORTED_FILE_TYPE -> 415;
            case DOCUMENT_TOO_LARGE -> 413;
            default -> 400;
        };
        return ResponseEntity.status(status).body(new ApiError(exception.code().name(), exception.getMessage(),
                Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ApiError("DOCUMENT_CONFLICT", "Document conflicts with stored data",
                Instant.now(), request.getRequestURI()));
    }
}
