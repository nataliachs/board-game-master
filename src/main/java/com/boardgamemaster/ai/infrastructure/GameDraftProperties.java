package com.boardgamemaster.ai.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits for drafting a whole game from a rulebook. A draft is much longer than a rule answer,
 * so it gets its own output budget and timeout; it uses the LLM provider, model and key.
 */
@ConfigurationProperties("boardgame.game-draft")
public record GameDraftProperties(Integer maxOutputTokens, Duration timeout) {
    public GameDraftProperties {
        maxOutputTokens = maxOutputTokens == null ? 16000 : maxOutputTokens;
        timeout = timeout == null ? Duration.ofSeconds(180) : timeout;
        if (maxOutputTokens < 1000 || maxOutputTokens > 32000
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(280)) > 0) {
            throw new IllegalArgumentException("GAME_DRAFT_MAX_OUTPUT_TOKENS must be 1000-32000 and GAME_DRAFT_TIMEOUT at most 280s");
        }
    }
    public static GameDraftProperties defaults() { return new GameDraftProperties(null, null); }
}
