package com.boardgamemaster.voice.application;

import com.boardgamemaster.ai.TextToSpeechClient;
import com.boardgamemaster.session.application.SessionService;
import com.boardgamemaster.session.domain.*;
import com.boardgamemaster.setup.application.SetupService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads authored guidance without changing progress or treating lessons as retrieved evidence. */
@Service
public class NarrationService {
    public record Result(UUID sessionId, long revision, SessionStage stage, String sectionKey,
            int segmentIndex, int totalSegments, String text, VoiceService.Audio audio) { }
    private record Guidance(SessionStage stage, String key, List<String> segments) { }
    private final SessionService sessions;
    private final SetupService setups;
    private final TextToSpeechClient tts;
    private final TransactionTemplate reads;

    public NarrationService(SessionService sessions, SetupService setups, TextToSpeechClient tts, PlatformTransactionManager manager) {
        this.sessions = sessions; this.setups = setups; this.tts = tts;
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Result speak(UUID id, long expectedRevision, boolean explainAgain, int segmentIndex) {
        var guidance = Objects.requireNonNull(reads.execute(status -> {
            var view = sessions.current(id, explainAgain);
            var session = view.session();
            session.requireActive();
            requireRevision(session, expectedRevision);
            if (session.stage() == SessionStage.TEACHING) {
                return new Guidance(session.stage(), session.currentSectionKey(), segments(view.lesson().title() + ".\n" + view.explanation()));
            }
            if (session.stage() == SessionStage.SETUP && !explainAgain) {
                var step = setups.current(id).step();
                var text = new StringBuilder(step.title()).append(".\n").append(step.instruction());
                step.notes().forEach(note -> text.append("\nNote: ").append(note));
                step.warnings().forEach(warning -> text.append("\nImportant: ").append(warning));
                return new Guidance(session.stage(), step.key(), segments(text.toString()));
            }
            throw new SessionException(SessionException.Code.INVALID_SESSION_ACTION, "Spoken guidance is available during teaching and setup");
        }));
        if (segmentIndex < 0 || segmentIndex >= guidance.segments().size()) {
            throw new SessionException(SessionException.Code.INVALID_SESSION, "Narration segment is out of range");
        }
        String text = guidance.segments().get(segmentIndex);
        var audio = tts.synthesize(text); // Never hold the read transaction across a provider call.
        var after = sessions.current(id).session();
        after.requireActive();
        requireRevision(after, expectedRevision);
        return new Result(id, expectedRevision, guidance.stage(), guidance.key(), segmentIndex, guidance.segments().size(), text,
                new VoiceService.Audio(audio.mediaType(), Base64.getEncoder().encodeToString(audio.bytes())));
    }

    private static void requireRevision(GameSession session, long expected) {
        if (session.revision() != expected) {
            throw new SessionException(SessionException.Code.SESSION_CONFLICT, "Session changed; reload before listening to this explanation");
        }
    }

    /** Request one bounded segment at a time, so stopping does not synthesize the rest of a long lesson. */
    static List<String> segments(String text) {
        var result = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + 4096, text.length());
            if (end < text.length()) {
                int boundary = text.lastIndexOf(' ', end);
                if (boundary > start + 2048) { end = boundary; }
                if (Character.isHighSurrogate(text.charAt(end - 1))) { end--; }
            }
            String part = text.substring(start, end).strip();
            if (!part.isEmpty()) { result.add(part); }
            start = end;
        }
        return List.copyOf(result);
    }
}
