package com.boardgamemaster.ai;

public class SpeechException extends RuntimeException {
    public enum Code { INVALID_AUDIO, AUDIO_TOO_LARGE, STT_NOT_CONFIGURED, STT_TIMEOUT, STT_RATE_LIMITED,
        STT_PROVIDER_UNAVAILABLE, STT_INVALID_RESPONSE, EMPTY_TRANSCRIPTION, TTS_NOT_CONFIGURED,
        TTS_TIMEOUT, TTS_RATE_LIMITED, TTS_PROVIDER_UNAVAILABLE, TTS_INVALID_RESPONSE, TTS_TEXT_TOO_LONG }
    private final Code code;
    public SpeechException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
    public int status() {
        return switch (code) {
            case INVALID_AUDIO, EMPTY_TRANSCRIPTION, TTS_TEXT_TOO_LONG -> 400;
            case AUDIO_TOO_LARGE -> 413;
            case STT_TIMEOUT, TTS_TIMEOUT -> 504;
            case STT_INVALID_RESPONSE, TTS_INVALID_RESPONSE -> 502;
            default -> 503;
        };
    }
}
