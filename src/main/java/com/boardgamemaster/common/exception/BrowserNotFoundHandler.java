package com.boardgamemaster.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Unknown addresses are answered by Spring MVC before the servlet error page. A person who mistypes
 * an address in the browser gets the friendly page; API paths and non-browser clients keep the JSON
 * {@code NOT_FOUND} body that {@link GlobalExceptionHandler} would have returned.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BrowserNotFoundHandler {
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    Object notFound(HttpServletRequest request) {
        if (wantsPage(request)) {
            var view = new ModelAndView("error", Map.of("error", ApiErrorController.pageMessage(404)));
            view.setStatus(HttpStatus.NOT_FOUND);
            return view;
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(404, request.getRequestURI()));
    }

    static boolean wantsPage(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return !request.getRequestURI().startsWith("/api/") && accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }
}
