package com.boardgamemaster.common.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * The request path as Spring MVC sees it when choosing a controller: URL-decoded, without
 * {@code ;path-parameters} and with repeated slashes collapsed. Filters that guard paths must
 * match on this, not the raw {@code getRequestURI()}, or {@code /admin;x} and {@code /%61dmin}
 * would skip the check yet still reach the {@code /admin} controller.
 */
final class RequestPaths {
    private RequestPaths() {}

    static String normalized(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
