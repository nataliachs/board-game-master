package com.boardgamemaster.session.domain;

import com.boardgamemaster.rag.application.RuleAnswer;
import java.time.Instant;
import java.util.UUID;

public record ConversationMessage(UUID id, UUID sessionId, long sequenceNumber, Role role, String content,
        RuleAnswer answer, Instant createdAt) {
    public enum Role { USER, ASSISTANT, SYSTEM }
}
