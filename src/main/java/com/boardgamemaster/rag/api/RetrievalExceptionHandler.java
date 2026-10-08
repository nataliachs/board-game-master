package com.boardgamemaster.rag.api;

import com.boardgamemaster.ai.EmbeddingException;
import com.boardgamemaster.ai.LlmException;
import com.boardgamemaster.game.domain.CatalogException;
import com.boardgamemaster.common.exception.ApiError;
import com.boardgamemaster.rag.application.RetrievalException;
import com.boardgamemaster.rules.domain.RuleImportException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {RuleSearchController.class, RuleQuestionController.class})
public class RetrievalExceptionHandler {
    @ExceptionHandler(CatalogException.class)
    ResponseEntity<ApiError> catalog(CatalogException e, HttpServletRequest request) {
        int status = switch (e.code()) {
            case GAME_NOT_FOUND, GAME_VERSION_NOT_FOUND -> 404;
            case GAME_SLUG_EXISTS, GAME_VERSION_EXISTS, GAME_INACTIVE, GAME_VERSION_INACTIVE,
                    DEFAULT_VERSION_NOT_CONFIGURED -> 409;
            default -> 400;
        };
        return error(status, e.code().name(), e.getMessage(), request);
    }

    @ExceptionHandler(LlmException.class)
    ResponseEntity<ApiError> llm(LlmException e, HttpServletRequest request) {
        int status = switch (e.code()) {
            case LLM_NOT_CONFIGURED, LLM_RATE_LIMITED, LLM_PROVIDER_UNAVAILABLE -> 503;
            case LLM_TIMEOUT -> 504;
            case LLM_INVALID_RESPONSE -> 502;
        };
        return error(status, e.code().name(), e.getMessage(), request);
    }

    @ExceptionHandler(EmbeddingException.class)
    ResponseEntity<ApiError> provider(EmbeddingException e, HttpServletRequest request) {
        int status = switch (e.code()) {
            case EMBEDDING_NOT_CONFIGURED, EMBEDDING_RATE_LIMITED, EMBEDDING_PROVIDER_UNAVAILABLE -> 503;
            case EMBEDDING_TIMEOUT -> 504;
            case EMBEDDING_INVALID_RESPONSE -> 502;
        };
        return error(status, e.code().name(), e.getMessage(), request);
    }

    @ExceptionHandler(RetrievalException.class)
    ResponseEntity<ApiError> retrieval(RetrievalException e, HttpServletRequest request) {
        int status = switch (e.code()) { case INDEX_CONFLICT -> 409; case INDEX_LIMIT_EXCEEDED -> 413; case RERANKING_FAILED -> 502; default -> 400; };
        return error(status, e.code().name(), e.getMessage(), request);
    }

    @ExceptionHandler(RuleImportException.class)
    ResponseEntity<ApiError> source(RuleImportException e, HttpServletRequest request) {
        int status = switch (e.code()) { case GAME_VERSION_NOT_FOUND, DOCUMENT_NOT_FOUND -> 404; default -> 400; };
        return error(status, e.code().name(), e.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(HttpServletRequest request) {
        return error(409, "INDEX_CONFLICT", "Embedding storage conflicts with rule data", request);
    }

    private ResponseEntity<ApiError> error(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Instant.now(), request.getRequestURI()));
    }
}
