package com.boardgamemaster.common.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

/**
 * Covers servlet errors that do not reach MVC advice, such as a page template failing while it
 * renders. Browsers asking for HTML get the friendly error page; everything else gets JSON.
 * The JSON content type is set explicitly because a failed page has already marked the response
 * as HTML, which would otherwise leave Spring unable to write the error and send an empty body.
 */
@RestController
public class ApiErrorController implements ErrorController {
    @RequestMapping(path = "${server.error.path:/error}", produces = MediaType.TEXT_HTML_VALUE)
    ModelAndView page(HttpServletRequest request) {
        int status = status(request);
        var view = new ModelAndView("error", Map.of("error", pageMessage(status)));
        view.setStatus(HttpStatus.valueOf(status));
        return view;
    }

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ApiError> error(HttpServletRequest request) {
        int status = status(request);
        Object pathAttribute = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = pathAttribute instanceof String value ? value : request.getRequestURI();
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(ApiError.of(status, path));
    }

    private static int status(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttribute instanceof Integer value ? value : 500;
        return HttpStatus.resolve(status) == null || status < 400 ? 500 : status;
    }

    /** Fixed, safe wording only; servlet error messages and exception text never reach the page. */
    static String pageMessage(int status) {
        return switch (status) {
            case 404 -> "That page doesn't exist. Check the address or return to game selection";
            case 400, 405, 413, 415 -> "That request couldn't be completed. Go back and try again";
            default -> status >= 500 ? "Something went wrong. Reload the page or return to game selection"
                    : "That request couldn't be completed. Go back and try again";
        };
    }
}
