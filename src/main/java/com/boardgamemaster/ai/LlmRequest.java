package com.boardgamemaster.ai;

public record LlmRequest(String instructions, String input) {
    public LlmRequest {
        if (instructions == null || instructions.isBlank() || instructions.length() > 8000
                || input == null || input.isBlank() || input.length() > 200000) {
            throw new IllegalArgumentException("LLM instructions and input must be nonblank and bounded");
        }
    }
    @Override public String toString() { return "LlmRequest[content=REDACTED]"; }
}
