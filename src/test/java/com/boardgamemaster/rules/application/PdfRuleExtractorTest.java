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
    void twoColumnPagesKeepEachColumnsSentencesTogether() {
        byte[] pdf = TestPdfs.twoColumns(
                List.of("Left column: draw two cards", "and then play one.", PdfRuleExtractorTest.FILLER),
                List.of("Right column: score three", "points for each set.", PdfRuleExtractorTest.FILLER));
        String text = new String(extractor.extract("rules.pdf", pdf).markdown(), StandardCharsets.UTF_8);
        assertThat(text).containsSubsequence("draw two cards", "and then play one.")
                .containsSubsequence("score three", "points for each set.");
        assertThat(text.indexOf("and then play one.")).isLessThan(text.indexOf("Right column"));
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

    private static String markdown(byte[] pdf) {
        String text = new String(new PdfRuleExtractor().extract("rules.pdf", pdf).markdown(), StandardCharsets.UTF_8);
        assertThat(text).as("position tags never reach the stored text").doesNotContain("", "");
        return text;
    }
    private static List<TextRuleProcessor.Chunk> chunks(byte[] pdf) {
        var result = new PdfRuleExtractor().extract("rules.pdf", pdf);
        return new TextRuleProcessor(new RuleIngestionProperties(1200, 200, 1_048_576, 10_000))
                .process(result.fileName(), result.markdown()).chunks();
    }

    @Test
    void largerTitlesBecomeHeadingsSoPassagesAreSplitAndTitledByTopic() {
        // Azul-like layout: 20 pt titles over 11 pt rules.
        byte[] pdf = TestPdfs.placed(List.of(List.of(
                TestPdfs.Placed.heading("Game Setup", 60, 20), TestPdfs.Placed.body("Give each player a player board.", 84),
                TestPdfs.Placed.body(FILLER, 98), TestPdfs.Placed.body(FILLER, 112),
                TestPdfs.Placed.heading("End of the game", 160, 20), TestPdfs.Placed.body("The game ends after a full row.", 184),
                TestPdfs.Placed.body(FILLER, 198), TestPdfs.Placed.body(FILLER, 212))));
        assertThat(markdown(pdf)).contains("## Game Setup\nGive each player", "## End of the game\nThe game ends");
        assertThat(chunks(pdf)).extracting(TextRuleProcessor.Chunk::sectionTitle).containsExactly("Game Setup", "End of the game");
        assertThat(chunks(pdf).get(1).content()).contains("The game ends after a full row.").doesNotContain("player board");
    }

    @Test
    void shortBoldLinesAtBodySizeAreRunInHeadingsButBoldSentencesAreNot() {
        byte[] pdf = TestPdfs.placed(List.of(List.of(
                TestPdfs.Placed.bold("Floor line", 60), TestPdfs.Placed.body("Excess tiles go to the floor line.", 74),
                TestPdfs.Placed.bold("Place the tiles one by one.", 100), TestPdfs.Placed.body(FILLER, 114),
                TestPdfs.Placed.body(FILLER, 128), TestPdfs.Placed.body(FILLER, 142))));
        String text = markdown(pdf);
        assertThat(text).contains("## Floor line\nExcess tiles").contains("\nPlace the tiles one by one.")
                .doesNotContain("## Place the tiles");
    }

    @Test
    void headingStoredAfterItsTextIsPlacedAboveTheLineDirectlyBelowIt() {
        // Dixit-like: the PDF stores the title after the paragraph it heads.
        byte[] pdf = TestPdfs.placed(List.of(List.of(
                TestPdfs.Placed.body("Introductory text for the whole game.", 40), TestPdfs.Placed.body(FILLER, 54),
                TestPdfs.Placed.body("The storyteller looks at their cards.", 120), TestPdfs.Placed.body(FILLER, 134),
                TestPdfs.Placed.body(FILLER, 148), TestPdfs.Placed.heading("Create the riddle", 104, 14))));
        String text = markdown(pdf);
        assertThat(text).contains("## Create the riddle\nThe storyteller looks at their cards.");
        assertThat(text.indexOf("Introductory text")).isLessThan(text.indexOf("## Create the riddle"));
    }

    @Test
    void labelsContinuedSentencesAndFarAwayTitlesStayPlainText() {
        byte[] pdf = TestPdfs.placed(List.of(List.of(
                // A large first line of a step whose sentence continues on the next line.
                TestPdfs.Placed.heading("Shuffle the deck", 60, 16), TestPdfs.Placed.body("and put it face down.", 78),
                // Two diagram labels in a row, then a large title with nothing near it.
                TestPdfs.Placed.heading("Pattern lines", 200, 16), TestPdfs.Placed.heading("Score track", 220, 16),
                TestPdfs.Placed.heading("Wall", 400, 16),
                TestPdfs.Placed.body(FILLER, 600), TestPdfs.Placed.body(FILLER, 614), TestPdfs.Placed.body(FILLER, 628))));
        assertThat(markdown(pdf)).doesNotContain("## ").contains("Shuffle the deck", "and put it face down.", "Pattern lines", "Wall");
    }

    @Test
    void layoutNoiseIsDroppedButRuleTextIsKept() {
        // A rotated cover title comes out of text-order extraction as two- or three-letter pieces
        // (Exploding Kittens: "EX", "PL", "OD"…); rotated text the PDF stores as whole lines is kept.
        byte[] pdf = TestPdfs.placed(List.of(List.of(
                new TestPdfs.Placed("EXPLODING", 20, 300, 30, true, true),
                TestPdfs.Placed.body("B", 60), TestPdfs.Placed.body("3", 74), TestPdfs.Placed.body("#1 Draw two cards.", 88),
                TestPdfs.Placed.body("OR", 102), TestPdfs.Placed.body(FILLER, 116), TestPdfs.Placed.body(FILLER, 130),
                TestPdfs.Placed.body(FILLER, 144))));
        String text = markdown(pdf);
        assertThat(text).doesNotContain("EX", "PL", "OD", "ING").doesNotContainPattern("(?m)^B$").doesNotContainPattern("(?m)^3$")
                .contains("\nOR\n", "1 Draw two cards.").doesNotContain("#1");
    }

    @Test
    void singleFontRulebooksGetNoHeadings() {
        byte[] pdf = TestPdfs.pdf(List.of(List.of("Setup", "Shuffle the deck and deal six cards.", FILLER, FILLER, FILLER)));
        assertThat(markdown(pdf)).doesNotContain("## ").contains("Setup\nShuffle the deck");
    }

    @Test
    void cleaningRemovesStructureTheChunkerWouldMisread() {
        String cleaned = PdfRuleExtractor.clean("Intro\u0007 text\r\n<!-- page: 9 -->\n```code\n\n\n\nEnd   ");
        assertThat(cleaned).isEqualTo("Intro text\ncode\n\nEnd");
    }
}
