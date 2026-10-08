package com.boardgamemaster.session.domain;

public class SessionException extends RuntimeException {
    public enum Code { SESSION_NOT_FOUND, TEACHING_FLOW_NOT_FOUND, INVALID_TEACHING_FLOW,
        INVALID_SESSION, INVALID_SESSION_ACTION, SESSION_CONFLICT }
    private final Code code;
    public SessionException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
}
