package com.boardgamemaster.rules;

import com.boardgamemaster.rules.application.*;
import com.boardgamemaster.rules.domain.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class TextRuleProcessorTest {
    private TextRuleProcessor processor(int size, int overlap) {
        return new TextRuleProcessor(new RuleIngestionProperties(size, overlap, 10000, 1000));
    }
    private byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test
    void cleansBomAndLineEndingsAndPreservesSectionsPagesAndExactOffsets() {
        var result = processor(100, 20).process("C:\\private\\rules.MD", utf8(
                "\uFEFF# Setup  \r\n\r\n\r\nPlace the blue token.  \r\n\r\n<!-- page: 3 -->\r\n## Turn\r\n\r\nDraw one card.\r\n"));
        assertThat(result.fileName()).isEqualTo("rules.MD");
        assertThat(result.text()).doesNotContain("\r", "\uFEFF", "\n\n\n", "  \n");
        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().getFirst().sectionTitle()).isEqualTo("Setup");
        assertThat(result.chunks().getFirst().pageNumber()).isNull();
        assertThat(result.chunks().getLast().sectionTitle()).isEqualTo("Turn");
        assertThat(result.chunks().getLast().pageNumber()).isEqualTo(3);
        // The page marker has no content before the next heading, hence produces no empty chunk.
        for (var chunk : result.chunks()) {
            assertThat(chunk.content()).isEqualTo(result.text().substring(chunk.startOffset(), chunk.endOffset()));
            assertThat(chunk.content()).doesNotContain("<!-- page:");
        }
    }

    @Test
    void keepsShortParagraphsWholeAndOverlapsWithoutRepeatingOnlyOldContent() {
        String first = "First paragraph takes thirty chars.";
        String second = "Shared context.";
        String third = "The final paragraph takes thirty chars.";
        var result = processor(64, 20).process("demo.txt", utf8(first + "\n\n" + second + "\n\n" + third));
        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().get(0).content()).isEqualTo(first + "\n\n" + second);
        assertThat(result.chunks().get(1).content()).isEqualTo(second + "\n\n" + third);
        assertThat(result.chunks().get(1).startOffset()).isLessThan(result.chunks().get(0).endOffset());
        assertThat(result.chunks().get(1).endOffset()).isEqualTo(result.text().length());
    }

    @Test
    void oversizedParagraphUsesSentenceThenWordBoundaries() {
        String sentence = "Move the token to the next empty space.";
        var result = processor(64, 0).process("demo.txt", utf8((sentence + " ").repeat(8).strip()));
        assertThat(result.chunks()).hasSize(8).allSatisfy(c -> assertThat(c.content()).isEqualTo(sentence));
    }

    @Test
    void longUnicodeTokensRemainBoundedAndAreNotCorrupted() {
        String text = "😀".repeat(200);
        var result = processor(65, 64).process("demo.txt", utf8(text));
        assertThat(result.chunks()).allSatisfy(c -> {
            assertThat(c.content().length()).isLessThanOrEqualTo(65);
            assertThat(Character.isLowSurrogate(c.content().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(c.content().charAt(c.content().length() - 1))).isFalse();
        });
        assertThat(result.chunks().stream().map(TextRuleProcessor.Chunk::content).reduce("", String::concat)).isEqualTo(text);
    }

    @Test
    void overlapNeverCrossesSectionsOrPages() {
        var result = processor(64, 63).process("demo.md", utf8("# A\n\nFirst.\n\n# B\n\nSecond.\n\n<!-- page: 2 -->\nThird."));
        assertThat(result.chunks()).hasSize(3);
        assertThat(result.chunks().get(1).content()).isEqualTo("# B\n\nSecond.");
        assertThat(result.chunks().get(2).content()).isEqualTo("Third.");
        assertThat(result.chunks().get(2).sectionTitle()).isEqualTo("B");
        assertThat(result.chunks().get(2).pageNumber()).isEqualTo(2);
    }

    static Stream<Arguments> badFiles() {
        return Stream.of(
                Arguments.of("book.pdf", "text".getBytes(StandardCharsets.UTF_8), "UNSUPPORTED_FILE_TYPE"),
                Arguments.of("empty.txt", new byte[0], "INVALID_DOCUMENT"),
                Arguments.of("blank.md", " \r\n\t".getBytes(StandardCharsets.UTF_8), "INVALID_DOCUMENT"),
                Arguments.of("binary.txt", new byte[]{65, 0, 66}, "INVALID_DOCUMENT"),
                Arguments.of("invalid.txt", new byte[]{(byte) 0xC3, 0x28}, "INVALID_DOCUMENT"),
                Arguments.of("page.md", "<!-- page: 0 -->".getBytes(StandardCharsets.UTF_8), "INVALID_DOCUMENT"),
                Arguments.of("marker.md", "<!-- page: 1 -->".getBytes(StandardCharsets.UTF_8), "INVALID_DOCUMENT"),
                Arguments.of("long.txt", new byte[10001], "DOCUMENT_TOO_LARGE"));
    }
    @ParameterizedTest @MethodSource("badFiles")
    void rejectsMalformedInput(String name, byte[] bytes, String code) {
        assertThatThrownBy(() -> processor(64, 0).process(name, bytes)).isInstanceOfSatisfying(RuleImportException.class,
                e -> assertThat(e.code().name()).isEqualTo(code));
    }

    @Test
    void limitsChunkExplosionBeforePersistence() {
        var processor = new TextRuleProcessor(new RuleIngestionProperties(64, 0, 10000, 2));
        assertThatThrownBy(() -> processor.process("many.txt", utf8("x".repeat(200))))
                .isInstanceOfSatisfying(RuleImportException.class, e -> assertThat(e.code()).isEqualTo(RuleImportException.Code.DOCUMENT_TOO_LARGE));
    }

    @Test
    void preservesFirstLineIndentationDuringCleanup() {
        var result = processor(1200, 0).process("demo.md", utf8("\n\n    # Literal heading  \n\nBody.\n"));
        assertThat(result.text()).isEqualTo("    # Literal heading\n\nBody.");
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().getFirst().sectionTitle()).isNull();
    }

    @Test
    void treatsFencedMarkdownAsTextRatherThanHeadingsOrPageMarkers() {
        var result = processor(1200, 0).process("demo.md", utf8("# Real\n\n```text\n# Example\n<!-- page: 0 -->\n```\n\nEnd."));
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().getFirst().sectionTitle()).isEqualTo("Real");
        assertThat(result.chunks().getFirst().pageNumber()).isNull();
        assertThat(result.chunks().getFirst().content()).contains("# Example", "<!-- page: 0 -->");
    }

    @Test
    void rejectsInvalidConfigurationAndMetadataForNonHttpCallers() {
        assertThatThrownBy(() -> new RuleIngestionProperties(64, 64, 1000, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleIngestionProperties(63, 0, 1000, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentMetadata(" ", RuleSourceType.FAQ, "1", "en", "own source")).isInstanceOf(RuleImportException.class);
        assertThatThrownBy(() -> new DocumentMetadata("Demo", null, "1", "en", "own source")).isInstanceOf(RuleImportException.class);
        assertThatThrownBy(() -> new DocumentMetadata("Demo", RuleSourceType.FAQ, "1", "english", "own source")).isInstanceOf(RuleImportException.class);
        assertThat(new DocumentMetadata(" Demo ", RuleSourceType.FAQ, " 1 ", "EN-us", " own source ").language()).isEqualTo("en-us");
    }
}
