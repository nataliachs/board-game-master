package com.boardgamemaster.ai.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Optional separate model for the supporting calls around an answer: query rewriting (follow-ups and
 * the empty-search retry) and evidence review (relevance selection and the cited-answer support check).
 * These calls are short and run one after another before and after the answer, so a faster model here
 * shortens every answer. A blank model keeps everything on {@code LLM_MODEL}. Provider, key, timeout
 * and output limit are shared with the answer model.
 */
@ConfigurationProperties("boardgame.llm-checks")
public record LlmCheckProperties(String model, String reasoningEffort) {
    @ConstructorBinding
    public LlmCheckProperties {
        model = model == null ? "" : model.strip();
        reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? "none" : reasoningEffort.strip();
        if (!model.isEmpty()) {
            // Same rules as the answer model; an invalid pair stops startup instead of failing per question.
            new LlmProperties("disabled", model, "", java.time.Duration.ofSeconds(1), 128, reasoningEffort);
        }
    }

    public boolean separate() { return !model.isEmpty(); }

    /** The answer model's settings with this model and effort, or the answer settings unchanged when blank. */
    LlmProperties applyTo(LlmProperties answer) {
        if (!separate()) { return answer; }
        return new LlmProperties(answer.provider(), model, answer.apiKey(), answer.timeout(), answer.maxOutputTokens(), reasoningEffort);
    }
}
