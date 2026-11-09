package com.boardgamemaster.game.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class GameDomainTest {
    @Test
    void metadataIsTrimmedAndOptionalBlanksBecomeNull() {
        var game = new Game("demo", " Demo ", " ", 2, 5, 30, " Description ", true);
        assertThat(game.getName()).isEqualTo("Demo");
        assertThat(game.getPublisher()).isNull();
        assertThat(game.getDescription()).isEqualTo("Description");
    }

    @Test
    void acceptsOptionalCategoryAndCoverImage() {
        var game = new Game("demo", "Demo", null, 2, 4, 30, null, true, " Family ", "/images/covers/demo.jpg");
        assertThat(game.getCategory()).isEqualTo("Family");
        assertThat(game.getCoverImageUrl()).isEqualTo("/images/covers/demo.jpg");
        var remote = new Game("demo", "Demo", null, 2, 4, 30, null, true, null, "https://example.com/demo.png");
        assertThat(remote.getCategory()).isNull();
        assertThat(remote.getCoverImageUrl()).isEqualTo("https://example.com/demo.png");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "http://example.com/a.png", "images/a.png", "/a b.png", "/a\"onerror.png"})
    void rejectsUnsafeCoverImageUrls(String url) {
        assertThatThrownBy(() -> new Game("demo", "Demo", null, 2, 4, 30, null, true, null, url))
                .isInstanceOfSatisfying(CatalogException.class,
                        exception -> assertThat(exception.code()).isEqualTo(CatalogException.Code.INVALID_GAME));
    }

    @ParameterizedTest
    @MethodSource("invalidGames")
    void rejectsInvalidGameMetadata(String slug, String name, int min, int max, int duration) {
        assertThatThrownBy(() -> new Game(slug, name, null, min, max, duration, null, true))
                .isInstanceOfSatisfying(CatalogException.class,
                        exception -> assertThat(exception.code()).isEqualTo(CatalogException.Code.INVALID_GAME));
    }

    static Stream<Arguments> invalidGames() {
        return Stream.of(
                Arguments.of("Bad Slug", "Demo", 1, 4, 30),
                Arguments.of("demo--game", "Demo", 1, 4, 30),
                Arguments.of("demo", " ", 1, 4, 30),
                Arguments.of("demo", "Demo", 0, 4, 30),
                Arguments.of("demo", "Demo", 4, 3, 30),
                Arguments.of("demo", "Demo", 1, 4, 0));
    }

    @Test
    void validPlayerCountsIncludeBothBoundaries() {
        var game = new Game("demo", "Demo", null, 2, 5, 30, null, true);
        assertThatCode(() -> { game.validatePlayerCount(2); game.validatePlayerCount(5); }).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 6})
    void rejectsOutOfRangePlayers(int players) {
        var game = new Game("demo", "Demo", null, 2, 5, 30, null, true);
        assertThatThrownBy(() -> game.validatePlayerCount(players)).isInstanceOfSatisfying(CatalogException.class,
                exception -> assertThat(exception.code()).isEqualTo(CatalogException.Code.INVALID_PLAYER_COUNT));
    }

    @Test
    void inactiveGameCannotBeResolved() {
        var game = new Game("demo", "Demo", null, 1, 4, 30, null, false);
        assertThatThrownBy(game::requireActive).isInstanceOfSatisfying(CatalogException.class,
                exception -> assertThat(exception.code()).isEqualTo(CatalogException.Code.GAME_INACTIVE));
    }

    @Test
    void versionNormalizesLanguageAndCanClearDefault() {
        var owner = UUID.randomUUID();
        var version = new GameVersion(owner, " First ", " Base ", "EN-us", null, true);
        assertThat(version.getGameId()).isEqualTo(owner);
        assertThat(version.getEdition()).isEqualTo("First");
        assertThat(version.getLanguage()).isEqualTo("en-us");
        version.makeDefault();
        assertThat(version.isDefaultVersion()).isTrue();
        version.clearDefault();
        assertThat(version.isDefaultVersion()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "English", "e", "en_US", "en-"})
    void rejectsInvalidLanguageTags(String language) {
        assertThatThrownBy(() -> new GameVersion(UUID.randomUUID(), "First", "base", language, null, true))
                .isInstanceOfSatisfying(CatalogException.class,
                        exception -> assertThat(exception.code()).isEqualTo(CatalogException.Code.INVALID_GAME_VERSION));
    }

    @Test
    void versionNeedsOwnerAndValidReleaseYear() {
        assertThatThrownBy(() -> new GameVersion(null, "First", "base", "en", null, true))
                .isInstanceOf(CatalogException.class);
        assertThatThrownBy(() -> new GameVersion(UUID.randomUUID(), "First", "base", "en", 0, true))
                .isInstanceOf(CatalogException.class);
        assertThatThrownBy(() -> new GameVersion(UUID.randomUUID(), "First", "base", "en", 10000, true))
                .isInstanceOf(CatalogException.class);
    }

    @Test
    void inactiveVersionCannotBecomeDefault() {
        var version = new GameVersion(UUID.randomUUID(), "First", "base", "en", null, false);
        assertThatThrownBy(version::makeDefault).isInstanceOfSatisfying(CatalogException.class,
                exception -> assertThat(exception.code()).isEqualTo(CatalogException.Code.GAME_VERSION_INACTIVE));
        assertThat(version.isDefaultVersion()).isFalse();
    }
}
