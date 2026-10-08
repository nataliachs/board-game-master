package com.boardgamemaster.voice.application;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.common.exception.PresentationFailure;
import com.boardgamemaster.session.application.SessionService;
import java.util.*;
import org.springframework.stereotype.Service;

/** Transcription and synthesis wrap the same persisted text pipeline; no provider call spans a DB transaction. */
@Service
public class VoiceService {
    public record Audio(String mediaType, String base64) {
        @Override public String toString() { return "Audio[content=REDACTED]"; }
    }
    public record Result(String transcription, SessionService.MessageResult message, Audio audio,
            PresentationFailure answerError, PresentationFailure audioError) { }
    private final SpeechToTextClient stt;
    private final TextToSpeechClient tts;
    private final SessionService sessions;
    private final AudioValidator validator;
    public VoiceService(SpeechToTextClient stt, TextToSpeechClient tts, SessionService sessions, AudioValidator validator) {
        this.stt = stt; this.tts = tts; this.sessions = sessions; this.validator = validator;
    }
    public Result ask(UUID id, byte[] bytes, String mediaType) {
        var input = validator.validate(bytes, mediaType);
        var before = sessions.current(id).session(); before.requireActive();
        String text = stt.transcribe(input);
        if (text == null || text.isBlank()) { throw new SpeechException(SpeechException.Code.EMPTY_TRANSCRIPTION, "I couldn't understand the audio. Please try again or type your question"); }
        if (text.length() > 4000) { throw new SpeechException(SpeechException.Code.STT_INVALID_RESPONSE, "Transcription is too long; record a shorter question"); }
        text = text.strip();
        SessionService.MessageResult message;
        try { message = sessions.ask(id, text, before.revision()); }
        catch (RuntimeException error) { return new Result(text, null, null, PresentationFailure.from(error), null); }
        try {
            var audio = tts.synthesize(message.answer().answer());
            return new Result(text, message, new Audio(audio.mediaType(), Base64.getEncoder().encodeToString(audio.bytes())), null, null);
        } catch (SpeechException error) {
            return new Result(text, message, null, null, PresentationFailure.from(error));
        }
    }
}
