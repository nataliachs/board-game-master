package com.boardgamemaster.voice.api;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.common.exception.*;
import com.boardgamemaster.session.domain.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = VoiceController.class)
public class VoiceExceptionHandler {
    @ExceptionHandler({SpeechException.class, SessionException.class})
    ResponseEntity<ApiError> error(RuntimeException error, HttpServletRequest request) {
        var safe = PresentationFailure.from(error);
        return ResponseEntity.status(safe.status()).cacheControl(CacheControl.noStore())
                .body(new ApiError(safe.code(), safe.message(), Instant.now(), request.getRequestURI()));
    }
}
