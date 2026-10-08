package com.boardgamemaster.voice.application;

import com.boardgamemaster.ai.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class AudioValidator {
    private final VoiceProperties config;
    public AudioValidator(VoiceProperties config) { this.config = config; }
    public AudioInput validate(byte[] bytes, String mediaType) {
        if (bytes != null && bytes.length > config.maxAudioBytes()) { throw new SpeechException(SpeechException.Code.AUDIO_TOO_LARGE, "Audio is too large; record a shorter question"); }
        if (bytes == null || bytes.length < 12 || mediaType == null) { throw invalid(); }
        String type = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        String extension = switch (type) {
            case "audio/wav", "audio/x-wav", "audio/wave" -> matches(bytes, 0, "RIFF") && matches(bytes, 8, "WAVE") ? "wav" : null;
            case "audio/webm" -> bytes[0] == 0x1a && (bytes[1] & 255) == 0x45 && (bytes[2] & 255) == 0xdf && (bytes[3] & 255) == 0xa3 ? "webm" : null;
            case "audio/ogg" -> matches(bytes, 0, "OggS") ? "ogg" : null;
            case "audio/mpeg", "audio/mp3" -> matches(bytes, 0, "ID3") || ((bytes[0] & 255) == 255 && (bytes[1] & 224) == 224) ? "mp3" : null;
            case "audio/mp4", "audio/x-m4a" -> matches(bytes, 4, "ftyp") ? "mp4" : null;
            default -> null;
        };
        if (extension == null) { throw invalid(); }
        String canonical = switch (extension) { case "wav" -> "audio/wav"; case "mp3" -> "audio/mpeg"; default -> "audio/" + extension; };
        return new AudioInput(bytes, canonical, extension);
    }
    private boolean matches(byte[] bytes, int offset, String text) {
        return new String(bytes, offset, text.length(), StandardCharsets.US_ASCII).equals(text);
    }
    private SpeechException invalid() { return new SpeechException(SpeechException.Code.INVALID_AUDIO, "Upload a nonempty WAV, WebM, Ogg, MP3 or MP4 audio recording"); }
}
