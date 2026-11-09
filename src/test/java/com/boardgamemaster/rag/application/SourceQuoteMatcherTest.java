package com.boardgamemaster.rag.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SourceQuoteMatcherTest {
    @Test void paragraphAndUnicodeWhitespaceDifferencesReturnOriginalSpan() {
        String original = "Deal four mission cards.\r\n\r\nKeep\t at\u00a0least two.";
        assertThat(SourceQuoteMatcher.find("## Setup\n\n" + original + "\nNext step.",
                " Deal four mission cards. Keep at least two. ")).contains(original);
        assertThat(SourceQuoteMatcher.find(original, original)).contains(original);
        assertThat(SourceQuoteMatcher.find("Keep two.", "Keep\n\ntwo.")).contains("Keep two.");
    }
    @ParameterizedTest @ValueSource(strings = {
        "Deal four mission cards. ... Keep at least two.",
        "Deal four mission cards. … Keep at least two.",
        "Deal five mission cards. Keep at least two.",
        "Deal four mission cards! Keep at least two.",
        "deal four mission cards. Keep at least two.",
        "Deal four cards. Keep at least two.",
        "Dealfour mission cards. Keep at least two.",
        "Keep at least two. Deal four mission cards."
    })
    void changedNonWhitespaceContentCannotPass(String quote) {
        assertThat(SourceQuoteMatcher.find("Deal four mission cards.\n\nKeep at least two.", quote)).isEmpty();
    }
    @Test void missingWhitespaceAndHiddenCharactersAreNotNormalizedAway() {
        assertThat(SourceQuoteMatcher.find("Keep two.", "Keeptwo.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("Keeptwo.", "Keep two.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("Keep two.", "Keep\u200btwo.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("Keep two.", "\u00a0\u00a0")).isEmpty();
    }
    @Test void quotesCannotSkipInterveningRulesAndRegexSyntaxIsLiteral() {
        assertThat(SourceQuoteMatcher.find("Deal four. Return extras. Keep two.", "Deal four. Keep two.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("Pay (2)\n[cards]+.", "Pay (2) [cards]+.")).contains("Pay (2)\n[cards]+.");
        assertThat(SourceQuoteMatcher.find("Pay two cards.", "Pay .* cards.")).isEmpty();
    }
    @Test void typographicPunctuationVariantsMatchAndReturnTheOriginalText() {
        String original = "The storyteller’s card – the “secret” one – can’t be voted for.";
        assertThat(SourceQuoteMatcher.find(original, "The storyteller's card - the \"secret\" one - can't be voted for."))
                .contains(original);
        assertThat(SourceQuoteMatcher.find("Don't draw.", "Don’t draw.")).contains("Don't draw.");
        assertThat(SourceQuoteMatcher.find("A 2-player game.", "A 2–player game.")).contains("A 2-player game.");
    }
    @Test void punctuationVariantsDoNotLoosenAnythingElse() {
        assertThat(SourceQuoteMatcher.find("Don’t draw.", "Dont draw.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("Don’t draw.", "Don’’t draw.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("Pay 2-3 cards.", "Pay 2 3 cards.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("Pay 2-3 cards.", "Pay 2-4 cards.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("“Go”", "'Go'")).isEmpty();
        assertThat(SourceQuoteMatcher.find("Pay a-b.", "Pay a]b.")).isEmpty();
    }
    @Test void markdownEmphasisAroundWordsIsSkippedAndTheOriginalSpanReturned() {
        String source = "- **Everyone found the storyteller's card, or nobody did:** the storyteller scores 0 points and\n"
                + "  every other player scores 2 points.";
        assertThat(SourceQuoteMatcher.find(source,
                "Everyone found the storyteller's card, or nobody did: the storyteller scores 0 points and every other player scores 2 points."))
                .contains("**Everyone found the storyteller's card, or nobody did:** the storyteller scores 0 points and\n"
                        + "  every other player scores 2 points.");
        assertThat(SourceQuoteMatcher.find("Draw _one_ card.", "Draw one card.")).contains("Draw _one_ card.");
        assertThat(SourceQuoteMatcher.find("A __bold__ move.", "A bold move.")).contains("A __bold__ move.");
    }
    @Test void emphasisSkippingDoesNotLoosenWordsOrNumbers() {
        assertThat(SourceQuoteMatcher.find("Pay 2*3 coins.", "Pay 23 coins.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("Keep**two.", "Keep two.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("**Score 3** points.", "Score 4 points.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("snake_case name", "snakecase name")).isEmpty();
        assertThat(SourceQuoteMatcher.find("Draw ****one card.", "Draw one card.")).isEmpty();
    }
    @Test void restoredQuotesRemainBounded() {
        assertThat(SourceQuoteMatcher.find("Keep" + " ".repeat(1000) + "two.", "Keep two.")).isEmpty();
        assertThat(SourceQuoteMatcher.find("x".repeat(1001), "x".repeat(1001))).isEmpty();
        assertThat(SourceQuoteMatcher.find("Keep" + " ".repeat(1000) + "two. Later: Keep\ntwo.", "Keep two."))
                .contains("Keep\ntwo.");
    }
}
