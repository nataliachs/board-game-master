package com.boardgamemaster.ai;

/** Safe public errors; never include raw provider responses, credentials or rule text. */
public class EmbeddingException extends RuntimeException {
    public enum Code { EMBEDDING_NOT_CONFIGURED, EMBEDDING_PROVIDER_UNAVAILABLE,
        EMBEDDING_RATE_LIMITED, EMBEDDING_TIMEOUT, EMBEDDING_INVALID_RESPONSE }
    private final Code code;
    public EmbeddingException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
}
