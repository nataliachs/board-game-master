package com.boardgamemaster.session.application;

import com.boardgamemaster.session.domain.ConversationMessage;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SessionContextTest {
    private ConversationMessage message(String content) {
        return new ConversationMessage(UUID.randomUUID(), UUID.randomUUID(), 1, ConversationMessage.Role.USER, content, null, Instant.now());
    }
    @Test void recentContextIsWholeMessageBoundedOrderedAndImmutable() {
        var selected = SessionContextService.bound(List.of(message("old"), message("middle"), message("new")), 9);
        assertThat(selected).extracting(t -> t.content()).containsExactly("middle", "new");
        assertThatThrownBy(() -> selected.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(SessionContextService.bound(List.of(message("oversized")), 3)).isEmpty();
    }
}
