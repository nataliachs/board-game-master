package com.boardgamemaster.ui.api;

import com.boardgamemaster.common.exception.PresentationFailure;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(assignableTypes = DemoUiController.class)
public class DemoUiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(DemoUiExceptionHandler.class);
    @ExceptionHandler(Exception.class)
    public String error(Exception error, Model model, HttpServletResponse response) {
        PresentationFailure safe;
        try {
            if (error instanceof org.springframework.web.bind.ServletRequestBindingException || error instanceof MethodArgumentTypeMismatchException) {
                safe = new PresentationFailure(400, "INVALID_REQUEST", "Check the form values and try again");
            } else if (error instanceof RuntimeException e) { safe = PresentationFailure.from(e); }
            else { throw new IllegalStateException(); }
        } catch (RuntimeException unknown) {
            LOG.error("Browser request failed: {}", error.getClass().getName());
            safe = new PresentationFailure(500, "INTERNAL_ERROR", "Something went wrong. Reload the page or return to game selection");
        }
        response.setStatus(safe.status()); model.addAttribute("error", safe.message()); return "error";
    }
}
