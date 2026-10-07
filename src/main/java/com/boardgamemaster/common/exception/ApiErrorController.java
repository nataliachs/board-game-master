package com.boardgamemaster.common.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Covers servlet errors that do not reach MVC advice. */
@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttribute instanceof Integer value ? value : 500;
        Object pathAttribute = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = pathAttribute instanceof String value ? value : request.getRequestURI();
        return ResponseEntity.status(status).body(ApiError.of(status, path));
    }
}
