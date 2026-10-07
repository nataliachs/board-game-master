package com.boardgamemaster.game.api;

import com.boardgamemaster.common.exception.ApiError;
import com.boardgamemaster.game.domain.CatalogException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = GameController.class)
public class CatalogExceptionHandler {
    @ExceptionHandler(CatalogException.class)
    ResponseEntity<ApiError> catalogError(CatalogException exception, HttpServletRequest request) {
        int status = switch (exception.code()) {
            case GAME_NOT_FOUND, GAME_VERSION_NOT_FOUND -> 404;
            case GAME_SLUG_EXISTS, GAME_VERSION_EXISTS, GAME_INACTIVE, GAME_VERSION_INACTIVE,
                    DEFAULT_VERSION_NOT_CONFIGURED -> 409;
            default -> 400;
        };
        return ResponseEntity.status(status).body(new ApiError(exception.code().name(), exception.getMessage(),
                Instant.now(), request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrityConflict(HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ApiError("CATALOG_CONFLICT",
                "Game configuration conflicts with existing data", Instant.now(), request.getRequestURI()));
    }
}
