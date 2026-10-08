package com.boardgamemaster.ai;

public record SpeechAudio(byte[] bytes, String mediaType) {
    public SpeechAudio {
        if (bytes == null || bytes.length < 3 || bytes.length > 4194304 || !"audio/mpeg".equals(mediaType)) {
            throw new SpeechException(SpeechException.Code.TTS_INVALID_RESPONSE, "Speech provider returned invalid audio");
        }
        boolean mp3 = (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3')
                || ((bytes[0] & 255) == 255 && (bytes[1] & 224) == 224);
        if (!mp3) { throw new SpeechException(SpeechException.Code.TTS_INVALID_RESPONSE, "Speech provider returned invalid audio"); }
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
    @Override public String toString() { return "SpeechAudio[content=REDACTED]"; }
}
