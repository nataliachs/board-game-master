package com.boardgamemaster.ui.application;

import static org.assertj.core.api.Assertions.*;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class AdminUiServiceTest {
    @Test
    void slugsComeFromTheGameName() {
        assertThat(AdminUiService.slugify("Ticket to Ride: Europe!")).isEqualTo("ticket-to-ride-europe");
        assertThat(AdminUiService.slugify("  7 Wonders ")).isEqualTo("7-wonders");
        assertThatThrownBy(() -> AdminUiService.slugify("!!!")).isInstanceOf(AdminUiService.AdminFormException.class);
    }

    @Test
    void keysAreUppercaseUniqueAndStartWithALetter() {
        var used = new HashSet<String>();
        assertThat(AdminUiService.key("", "Place the board", "STEP", used)).isEqualTo("PLACE_THE_BOARD");
        assertThat(AdminUiService.key("", "Place the board", "STEP", used)).isEqualTo("PLACE_THE_BOARD_2");
        assertThat(AdminUiService.key("", "2 players", "LESSON", used)).isEqualTo("LESSON_2_PLAYERS");
        assertThat(AdminUiService.key("my key", "ignored", "STEP", used)).isEqualTo("MY_KEY");
    }
}
