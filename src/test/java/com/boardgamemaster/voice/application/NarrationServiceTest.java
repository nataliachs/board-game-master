package com.boardgamemaster.voice.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NarrationServiceTest {
    @Test void longGuidanceIsBoundedWithoutDroppingTextOrBreakingSurrogates() {
        String text = "An original explanation. ".repeat(1200) + "x".repeat(4095) + "\uD83C\uDFB2" + "z".repeat(5000);
        var segments = NarrationService.segments(text);
        assertThat(segments).hasSizeGreaterThan(2).allSatisfy(part -> {
            assertThat(part).isNotBlank().hasSizeLessThanOrEqualTo(4096);
            assertThat(Character.isHighSurrogate(part.charAt(part.length() - 1))).isFalse();
            assertThat(Character.isLowSurrogate(part.charAt(0))).isFalse();
        });
        assertThat(String.join("", segments).replaceAll("\\s+", ""))
                .isEqualTo(text.replaceAll("\\s+", ""));
    }
}
