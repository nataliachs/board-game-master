package com.boardgamemaster.voice.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boardgame.voice")
public record VoiceProperties(Integer maxAudioBytes) {
    public VoiceProperties {
        maxAudioBytes = maxAudioBytes == null ? 2097152 : maxAudioBytes;
        if (maxAudioBytes < 1024 || maxAudioBytes > 8388608) { throw new IllegalArgumentException("Voice upload limit must be 1024–8388608 bytes"); }
    }
}
