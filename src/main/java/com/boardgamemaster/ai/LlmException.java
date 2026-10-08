package com.boardgamemaster.ai;

public class LlmException extends RuntimeException {
    public enum Code { LLM_NOT_CONFIGURED, LLM_PROVIDER_UNAVAILABLE, LLM_RATE_LIMITED, LLM_TIMEOUT, LLM_INVALID_RESPONSE }
    private final Code code;
    public LlmException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
}
