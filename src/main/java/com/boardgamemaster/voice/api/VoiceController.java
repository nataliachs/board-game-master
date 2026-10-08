package com.boardgamemaster.voice.api;

import com.boardgamemaster.voice.application.*;
import com.boardgamemaster.ai.SpeechException;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
@RequestMapping("/api/v1/sessions/{id}/voice")
public class VoiceController {
    private final VoiceService voice;
    private final VoiceProperties config;
    private final NarrationService narration;
    public VoiceController(VoiceService voice, VoiceProperties config, NarrationService narration) {
        this.voice = voice; this.config = config; this.narration = narration;
    }
    public record NarrationRequest(@NotNull @PositiveOrZero Long expectedRevision, boolean explainAgain, @Min(0) int segmentIndex) { }
    @PostMapping(value = "/narration", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Speak one segment of the current authored lesson or setup step without changing progress")
    public ResponseEntity<NarrationService.Result> narrate(@PathVariable UUID id, @Valid @RequestBody NarrationRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(narration.speak(id, request.expectedRevision(), request.explainAgain(), request.segmentIndex()));
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Transcribe audio through the existing session question pipeline; text survives synthesis failure")
    public ResponseEntity<VoiceService.Result> ask(@PathVariable UUID id, @RequestPart("audio") MultipartFile file) throws IOException {
        if (file.getSize() > config.maxAudioBytes()) { throw new SpeechException(SpeechException.Code.AUDIO_TOO_LARGE, "Audio is too large; record a shorter question"); }
        var result = voice.ask(id, file.getBytes(), file.getContentType());
        return ResponseEntity.status(result.answerError() == null ? 200 : result.answerError().status())
                .cacheControl(CacheControl.noStore()).body(result);
    }
}
