package com.boardgamemaster.setup.domain;

public class SetupException extends RuntimeException {
    public enum Code { SETUP_FLOW_NOT_FOUND, INVALID_SETUP_FLOW, INVALID_SETUP_ACTION }
    private final Code code;
    public SetupException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
}
