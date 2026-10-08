package com.boardgamemaster.voice;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.voice.application.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class AudioValidatorTest {
    private final AudioValidator validator = new AudioValidator(new VoiceProperties(1024));
    private byte[] audio(String signature) { return (signature + " original audio fixture padding").getBytes(StandardCharsets.US_ASCII); }
    @ParameterizedTest @CsvSource({"audio/wav,RIFF1234WAVE,wav", "audio/x-wav,RIFF1234WAVE,wav", "audio/ogg,OggS,ogg", "audio/mpeg,ID3,mp3", "audio/mp4,1234ftyp,mp4"})
    void validatesContainerAndNormalizes(String type, String signature, String extension) {
        var input = validator.validate(audio(signature), type); assertThat(input.extension()).isEqualTo(extension);
        var bytes = input.bytes(); bytes[0] = 0; assertThat(input.bytes()[0]).isNotZero();
        assertThat(input.toString()).doesNotContain("fixture");
    }
    @Test void webmCodecParameterAndBinaryHeaderSupported() {
        var bytes = new byte[20]; bytes[0]=0x1a;bytes[1]=0x45;bytes[2]=(byte)0xdf;bytes[3]=(byte)0xa3;
        assertThat(validator.validate(bytes, "audio/webm;codecs=opus").mediaType()).isEqualTo("audio/webm");
    }
    @Test void rejectsSpoofedEmptyUnknownAndOversizedAudio() {
        assertThatThrownBy(() -> validator.validate(audio("<html>"), "audio/wav")).isInstanceOf(SpeechException.class);
        assertThatThrownBy(() -> validator.validate(audio("ID3"), "text/plain")).isInstanceOf(SpeechException.class);
        assertThatThrownBy(() -> validator.validate(new byte[0], "audio/wav")).isInstanceOf(SpeechException.class);
        assertThatThrownBy(() -> validator.validate(null, null)).isInstanceOf(SpeechException.class);
        assertThatThrownBy(() -> validator.validate(new byte[1025], "audio/wav")).extracting(e -> ((SpeechException)e).code()).isEqualTo(SpeechException.Code.AUDIO_TOO_LARGE);
    }
    @Test void validatesLimitsAndOutputAudio() {
        assertThat(new VoiceProperties(null).maxAudioBytes()).isEqualTo(2097152);
        assertThatThrownBy(() -> new VoiceProperties(1023)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoiceProperties(8388609)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeechAudio(new byte[0], "audio/mpeg")).isInstanceOf(SpeechException.class);
        assertThatThrownBy(() -> new SpeechAudio(audio("<html>"), "audio/mpeg")).isInstanceOf(SpeechException.class);
        var source=audio("ID3");var output=new SpeechAudio(source,"audio/mpeg"); source[0]=0;
        assertThat(output.bytes()[0]).isEqualTo((byte)'I'); assertThat(output.toString()).contains("REDACTED");
    }
}
