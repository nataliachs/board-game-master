package com.boardgamemaster.rag.application;

public class RetrievalException extends RuntimeException {
    public enum Code { INVALID_SEARCH, INDEX_LIMIT_EXCEEDED, INDEX_CONFLICT, RERANKING_FAILED }
    private final Code code;
    public RetrievalException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
}
