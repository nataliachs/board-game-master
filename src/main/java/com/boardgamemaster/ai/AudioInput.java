package com.boardgamemaster.ai;

/** Validated binary audio, never a user-controlled filename or filesystem reference. */
public record AudioInput(byte[] bytes, String mediaType, String extension) {
    public AudioInput {
        if (bytes == null || bytes.length < 12 || mediaType == null || extension == null
                || !java.util.Set.of("audio/wav", "audio/webm", "audio/ogg", "audio/mpeg", "audio/mp4").contains(mediaType)
                || !java.util.Set.of("wav", "webm", "ogg", "mp3", "mp4").contains(extension)) {
            throw new SpeechException(SpeechException.Code.INVALID_AUDIO, "Invalid audio input");
        }
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
    @Override public String toString() { return "AudioInput[content=REDACTED]"; }
}
