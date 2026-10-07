package com.boardgamemaster.common.exception;

import java.time.Instant;

public record ApiError(String code, String message, Instant timestamp, String path) {
    public static ApiError of(int status, String path) {
        String code = switch (status) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
        String message = switch (status) {
            case 400 -> "Request is malformed or failed validation";
            case 404 -> "Resource was not found";
            case 405 -> "HTTP method is not supported for this resource";
            case 406 -> "Requested response format is not supported";
            case 413 -> "Request exceeds the upload limit";
            case 415 -> "Request content type is not supported";
            default -> status >= 500 ? "An unexpected error occurred" : "Request could not be completed";
        };
        return new ApiError(code, message, Instant.now(), path);
    }
}
