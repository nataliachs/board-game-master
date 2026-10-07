package com.boardgamemaster.rules.domain;

public class RuleImportException extends RuntimeException {
    public enum Code { INVALID_DOCUMENT, UNSUPPORTED_FILE_TYPE, DOCUMENT_TOO_LARGE,
        GAME_VERSION_NOT_FOUND, DOCUMENT_NOT_FOUND, INVALID_PAGINATION }
    private final Code code;

    public RuleImportException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
