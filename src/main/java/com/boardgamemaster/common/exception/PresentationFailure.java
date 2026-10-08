package com.boardgamemaster.common.exception;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.game.domain.CatalogException;
import com.boardgamemaster.session.domain.SessionException;
import com.boardgamemaster.setup.domain.SetupException;
import com.boardgamemaster.rag.application.RetrievalException;

/** Maps only application-owned, safe errors; never passes arbitrary exception/provider text to a page. */
public record PresentationFailure(int status, String code, String message) {
    public static PresentationFailure from(RuntimeException error) {
        if (error instanceof SpeechException e) { return new PresentationFailure(e.status(), e.code().name(), e.getMessage()); }
        if (error instanceof SessionException e) { return new PresentationFailure(switch(e.code()) { case SESSION_NOT_FOUND -> 404; case INVALID_SESSION_ACTION, SESSION_CONFLICT, TEACHING_FLOW_NOT_FOUND -> 409; default -> 400; }, e.code().name(), e.getMessage()); }
        if (error instanceof SetupException e) { return new PresentationFailure(e.code() == SetupException.Code.INVALID_SETUP_FLOW ? 400 : 409, e.code().name(), e.getMessage()); }
        if (error instanceof CatalogException e) { return new PresentationFailure(switch(e.code()) { case GAME_NOT_FOUND, GAME_VERSION_NOT_FOUND -> 404; case GAME_SLUG_EXISTS, GAME_VERSION_EXISTS, GAME_INACTIVE, GAME_VERSION_INACTIVE, DEFAULT_VERSION_NOT_CONFIGURED -> 409; default -> 400; }, e.code().name(), e.getMessage()); }
        if (error instanceof EmbeddingException e) { return new PresentationFailure(switch(e.code()) { case EMBEDDING_TIMEOUT -> 504; case EMBEDDING_INVALID_RESPONSE -> 502; default -> 503; }, e.code().name(), e.getMessage()); }
        if (error instanceof LlmException e) { return new PresentationFailure(switch(e.code()) { case LLM_TIMEOUT -> 504; case LLM_INVALID_RESPONSE -> 502; default -> 503; }, e.code().name(), e.getMessage()); }
        if (error instanceof RetrievalException e) { return new PresentationFailure(switch(e.code()) { case INDEX_CONFLICT -> 409; case INDEX_LIMIT_EXCEEDED -> 413; case RERANKING_FAILED -> 502; default -> 400; }, e.code().name(), e.getMessage()); }
        throw error;
    }
}
