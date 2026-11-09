package com.boardgamemaster.ai;

/** Drafts a game's teaching content from rulebook text; the admin reviews the result before publishing. */
public interface GameDraftClient {
    GameDraft draftGame(LlmRequest request);
}
