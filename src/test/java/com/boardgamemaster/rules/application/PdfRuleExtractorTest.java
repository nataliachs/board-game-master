package com.boardgamemaster.rules.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.boardgamemaster.rules.domain.RuleImportException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PdfRuleExtractorTest {
    private final PdfRuleExtractor extractor = new PdfRuleExtractor();
    static final String FILLER = "Players take turns clockwise and each turn has exactly one action to perform.";

    @Test
    void marksEachPageSoCitationsKeepPageNumbers() {
        byte[] pdf = TestPdfs.pdf(List.of(List.of("Setup", "Shuffle the deck and deal six cards.", FILLER, FILLER),
                List.of("Scoring", "The storyteller scores three points.", FILLER, FILLER)));
        var result = extractor.extract("C:\\rules\\My_Game Rules.PDF", pdf);
        String text = new String(result.markdown(), StandardCharsets.UTF_8);
        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.fileName()).isEqualTo("My_Game Rules.md");
        assertThat(text).startsWith("<!-- page: 1 -->").contains("<!-- page: 2 -->", "deal six cards", "three points");
        assertThat(text.indexOf("deal six cards")).isLessThan(text.indexOf("<!-- page: 2 -->"));
    }

    @Test
    void extractedTextFeedsTheRuleChunkerWithPageNumbers() {
        byte[] pdf = TestPdfs.pdf(List.of(List.of("First page rules.", FILLER, FILLER, FILLER),
                List.of("Second page rules.", FILLER, FILLER, FILLER)));
        var result = extractor.extract("rules.pdf", pdf);
        var chunks = new TextRuleProcessor(new RuleIngestionProperties(1200, 200, 1_048_576, 10_000))
                .process(result.fileName(), result.markdown()).chunks();
        assertThat(chunks).extracting(TextRuleProcessor.Chunk::pageNumber).containsExactly(1, 2);
        assertThat(chunks.get(1).content()).contains("Second page rules.");
    }

    @Test
    void rejectsPdfsWithoutReadableText() {
        byte[] blank = TestPdfs.pdf(List.of(List.of(), List.of("Hi")));
        assertThatThrownBy(() -> extractor.extract("scan.pdf", blank)).isInstanceOf(RuleImportException.class)
                .hasMessageContaining("selectable text");
    }

    @Test
    void rejectsFilesThatAreNotPdfs() {
        assertThatThrownBy(() -> extractor.extract("rules.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RuleImportException.class).hasMessageContaining("could not be read as a PDF");
        assertThatThrownBy(() -> extractor.extract("rules.txt", new byte[] {1})).isInstanceOf(RuleImportException.class);
        assertThatThrownBy(() -> extractor.extract("rules.pdf", new byte[0])).isInstanceOf(RuleImportException.class);
    }

    @Test
    void cleaningRemovesStructureTheChunkerWouldMisread() {
        String cleaned = PdfRuleExtractor.clean("Intro\u0007 text\r\n<!-- page: 9 -->\n```code\n\n\n\nEnd   ");
        assertThat(cleaned).isEqualTo("Intro text\ncode\n\nEnd");
    }
}
