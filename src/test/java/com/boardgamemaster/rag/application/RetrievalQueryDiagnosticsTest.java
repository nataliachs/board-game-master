package com.boardgamemaster.rag.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.boardgamemaster.rules.domain.RuleSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;

class RetrievalQueryDiagnosticsTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.boardgamemaster.rag.querydiagnostics");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previous;
    private final UUID version = UUID.randomUUID();
    @BeforeEach void start() { previous = logger.getLevel(); logs.start(); logger.addAppender(logs); }
    @AfterEach void stop() { logger.detachAppender(logs); logs.stop(); logger.setLevel(previous); }
    private RuleVectorStore.Match match(double score) {
        return new RuleVectorStore.Match(UUID.randomUUID(), UUID.randomUUID(), version, "PRIVATE RULE BODY",
                "Private document name", RuleSourceType.RULEBOOK, "1", "en", "Private source reference",
                "Final scoring", 2, 0, 0, 17, score);
    }
    @Test void ordinaryLoggingDoesNotExposeQueryOrCandidateDetails() {
        logger.setLevel(Level.INFO);
        RetrievalQueryDiagnostics.log(version, "private question", List.of(match(.49)), .5);
        assertThat(logs.list).isEmpty();
    }
    @Test void optInShowsEffectiveQueryAndRejectedCandidateScoresWithoutRuleBodies() throws Exception {
        logger.setLevel(Level.DEBUG);
        var rejected = match(.49);
        var accepted = match(.51);
        RetrievalQueryDiagnostics.log(version, "What are the consequences?\nSecond line", List.of(rejected, accepted), .5);
        assertThat(logs.list).hasSize(1);
        String message = logs.list.getFirst().getFormattedMessage();
        assertThat(message).doesNotContain("PRIVATE RULE BODY", "Private document name", "Private source reference");
        var detail = new ObjectMapper().readTree(message.substring(message.indexOf('{')));
        assertThat(detail.path("query").asText()).isEqualTo("What are the consequences?\nSecond line");
        assertThat(detail.path("candidates").get(0).path("chunkId").asText()).isEqualTo(rejected.chunkId().toString());
        assertThat(detail.path("candidates").get(0).path("passesThreshold").asBoolean()).isFalse();
        assertThat(detail.path("candidates").get(1).path("passesThreshold").asBoolean()).isTrue();
    }
    @Test void optInBoundsQueryAndCandidateCountWithoutSplittingUnicode() throws Exception {
        logger.setLevel(Level.DEBUG);
        String query = "q".repeat(999) + "😀" + "hidden tail";
        RetrievalQueryDiagnostics.log(version, query, Collections.nCopies(501, match(.4)), .5);
        String message = logs.list.getFirst().getFormattedMessage();
        var detail = new ObjectMapper().readTree(message.substring(message.indexOf('{')));
        assertThat(detail.path("query").asText()).isEqualTo("q".repeat(999));
        assertThat(detail.path("queryTruncated").asBoolean()).isTrue();
        assertThat(detail.path("queryCharacters").asInt()).isEqualTo(query.length());
        assertThat(detail.path("candidates").size()).isEqualTo(500);
        assertThat(detail.path("candidatesTruncated").asBoolean()).isTrue();
    }
}
