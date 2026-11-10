package com.boardgamemaster.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.boardgamemaster.game.api.GameResponses.GameResponse;
import com.boardgamemaster.ui.application.DemoUiService.Capabilities;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** Renders the home page template without a database: the shelf and chat are built from this markup. */
class HomeTemplateTest {
    private static final GameResponse WITH_COVER = new GameResponse(UUID.randomUUID(), "lantern-trail", "Lantern Trail", "Example",
            2, 5, 45, "Light the path <home>.", true, "Family", "https://covers.example/lantern-trail.jpg");
    private static final GameResponse NO_COVER = new GameResponse(UUID.randomUUID(), "solo-stack", "Solo Stack", null,
            3, 3, 20, null, true, null, null);

    private static String render(List<GameResponse> games, boolean voiceOutput) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine.process("home", new Context(null, Map.of("games", games, "hasNext", false, "gamePage", 0,
                "voice", new Capabilities(false, voiceOutput, 1024))));
    }

    @Test
    void rendersEveryGameWithTheDataTheShelfNeeds() {
        String html = render(List.of(WITH_COVER, NO_COVER), true);
        assertThat(html).contains("value=\"" + WITH_COVER.id() + "\"", "checked=\"checked\"", "data-name=\"Lantern Trail\"", "data-min=\"2\"", "data-max=\"5\"",
                "data-duration=\"45\"", "data-category=\"Family\"", "data-cover=\"https://covers.example/lantern-trail.jpg\"", "data-initials=\"LT\"",
                "Light the path &lt;home&gt;.", "2–5 players", "About 45 min");
        // A game without a cover or category still renders; the page draws a stand-in cover for it.
        assertThat(html).contains("value=\"" + NO_COVER.id() + "\"", "data-name=\"Solo Stack\"", "3 players · about 20 min");
        assertThat(html).contains("action=\"/ui/sessions\"", "name=\"playerCount\"", "min=\"2\"", "max=\"5\"", "value=\"STANDARD\" checked",
                "id=\"spoken-guidance-choice\"", "id=\"ask-form\"", "Asking about <b id=\"chat-game\">Lantern Trail</b>");
        // The page's CSP forbids inline styles and scripts.
        assertThat(html).doesNotContain("style=", "<style", "<script>", "onclick=");
    }

    @Test
    void emptyCatalogShowsAMessageInsteadOfTheShelfAndChat() {
        String html = render(List.of(), false);
        assertThat(html).contains("No active games on this page").doesNotContain("id=\"shelf-boxes\"", "id=\"ask-form\"", "id=\"spoken-guidance-choice\"");
    }
}
