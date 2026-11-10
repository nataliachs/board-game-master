package com.boardgamemaster.rag.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.boardgamemaster.ai.LlmResponse;
import com.boardgamemaster.rules.domain.RuleSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;

class QuoteMismatchDiagnosticsTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(QuoteMismatchDiagnostics.LOGGER_NAME);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;
    private final UUID version = UUID.randomUUID();

    @BeforeEach void attach() {
        previousLevel = logger.getLevel();
        events.start(); logger.addAppender(events);
    }
    @AfterEach void detach() {
        logger.detachAppender(events); events.stop(); logger.setLevel(previousLevel);
    }
    private RuleVectorStore.Match source(String content) {
        return new RuleVectorStore.Match(UUID.randomUUID(), UUID.randomUUID(), version, content, "Original rules",
                RuleSourceType.RULEBOOK, "1", "en", "Original test fixture", "Cards", 1, 0, 0, content.length(), .7);
    }
    @Test void rejectedAnswersAreLoggedOnlyWhenOptedIn() throws Exception {
        var cited = source("Treat the card that was Noped as if it had never been played.");
        var answer = new LlmResponse("Person C takes 2 turns.", true, List.of(new LlmResponse.Citation(cited.chunkId(), "as if it had never been played")));
        logger.setLevel(Level.OFF);
        QuoteMismatchDiagnostics.logRejectedAnswer("ANSWER_SUPPORT_REJECTED", version, "Who takes the turns?", answer);
        assertThat(events.list).isEmpty();

        logger.setLevel(Level.DEBUG);
        QuoteMismatchDiagnostics.logRejectedAnswer("ANSWER_SUPPORT_REJECTED", version, "Who takes the turns?", answer);
        String message = events.list.getFirst().getFormattedMessage();
        assertThat(message).startsWith("Rule answer rejection detail: ");
        var json = new ObjectMapper().readTree(message.substring("Rule answer rejection detail: ".length()));
        assertThat(json.path("reason").asText()).isEqualTo("ANSWER_SUPPORT_REJECTED");
        assertThat(json.path("question").asText()).isEqualTo("Who takes the turns?");
        assertThat(json.path("proposedAnswer").asText()).isEqualTo("Person C takes 2 turns.");
        assertThat(json.path("citations").get(0).path("quote").asText()).isEqualTo("as if it had never been played");
        assertThat(json.path("citations").get(0).path("chunkId").asText()).isEqualTo(cited.chunkId().toString());
    }
    @Test void disabledDiagnosticsEmitNoSourceContent() {
        logger.setLevel(Level.OFF);
        var source = source("Private fixture rule");
        QuoteMismatchDiagnostics.log(List.of(source), source, new LlmResponse.Citation(source.chunkId(), "Private rejected quote"), 1);
        assertThat(events.list).isEmpty();
    }
    @Test void optInPreservesExactTextAndIdentifiesQuoteAssignedToWrongChunk() throws Exception {
        logger.setLevel(Level.DEBUG);
        var cited = source("Deal four mission cards.\nKeep at least two.");
        String quote = "A fictional quote with \"punctuation\".\n\tKeep three.";
        var other = source(quote);
        QuoteMismatchDiagnostics.log(List.of(cited, other), cited, new LlmResponse.Citation(cited.chunkId(), quote), 2);
        assertThat(events.list).hasSize(1);
        String message = events.list.getFirst().getFormattedMessage();
        assertThat(message).startsWith("Rule quote mismatch detail: ").doesNotContain("\n", "\t");
        var json = new ObjectMapper().readTree(message.substring("Rule quote mismatch detail: ".length()));
        assertThat(json.path("attempt").asInt()).isEqualTo(2);
        assertThat(json.path("quote").asText()).isEqualTo(quote);
        assertThat(json.path("citedContent").asText()).isEqualTo(cited.content());
        assertThat(json.path("chunkId").asText()).isEqualTo(cited.chunkId().toString());
        assertThat(json.path("contentTruncated").asBoolean()).isFalse();
        assertThat(json.path("exactQuoteFoundInOtherChunkIds").get(0).asText()).isEqualTo(other.chunkId().toString());
        assertThat(json.has("question")).isFalse();
        assertThat(json.has("answer")).isFalse();
    }
    @Test void longPassagesAreBoundedWithoutSplittingUnicodeAndTruncationIsExplicit() throws Exception {
        logger.setLevel(Level.DEBUG);
        var cited = source("x".repeat(7999) + "😀tail");
        QuoteMismatchDiagnostics.log(List.of(cited), cited, new LlmResponse.Citation(cited.chunkId(), "Absent"), 1);
        var json = new ObjectMapper().readTree(events.list.getFirst().getFormattedMessage().substring("Rule quote mismatch detail: ".length()));
        assertThat(json.path("citedContent").asText()).hasSize(7999);
        assertThat(json.path("contentTruncated").asBoolean()).isTrue();
        assertThat(json.path("contentCharacters").asInt()).isEqualTo(cited.content().length());
        assertThat(json.path("exactQuoteFoundInOtherChunkIds").size()).isZero();
    }
}
