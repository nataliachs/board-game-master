package com.boardgamemaster.rag.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** Pins two behaviours found in live Dixit testing: complete outcomes, and contextual follow-ups in prod. */
class AnswerCompletenessConfigTest {
    @Test
    void answersMustCoverEveryAffectedPlayersOutcome() {
        var prompt = new RuleAnswerPromptBuilder(new ObjectMapper(), new AnswerProperties(.5, .8, 24000))
                .build(UUID.randomUUID(), "What if nobody finds the storyteller's card?", List.of());
        assertThat(prompt.request().instructions())
                .contains("give every outcome the cited excerpts state", "for each player or role it affects",
                        "Concise means short, not incomplete.");
    }

    @Test
    void excerptsReachTheModelWithPlainPunctuationOneForOne() {
        String stored = "You can play as many cards as you’d like – then say “done”.";
        String plain = RuleAnswerPromptBuilder.plainPunctuation(stored);
        assertThat(plain).isEqualTo("You can play as many cards as you'd like - then say \"done\".").hasSameSizeAs(stored);
        UUID version = UUID.randomUUID();
        var match = new RuleVectorStore.Match(UUID.randomUUID(), UUID.randomUUID(), version, stored, "Rulebook",
                com.boardgamemaster.rules.domain.RuleSourceType.RULEBOOK, "1", "en", "Imported", null, 1, 0, 0, stored.length(), .8);
        var prompt = new RuleAnswerPromptBuilder(new ObjectMapper(), new AnswerProperties(.5, .8, 24000))
                .build(version, "How many cards can I play?", List.of(match));
        assertThat(prompt.request().input()).contains("as you'd like - then").doesNotContain("’");
        assertThat(prompt.evidence().getFirst().content()).isEqualTo(stored);
        assertThat(SourceQuoteMatcher.find(stored, "You can play as many cards as you'd like")).contains("You can play as many cards as you’d like");
    }

    @Test
    void followUpReferencesPointAtWhatTheUserAskedAbout() {
        var instructions = new RuleAnswerPromptBuilder(new ObjectMapper(), new AnswerProperties(.5, .8, 24000))
                .build(UUID.randomUUID(), "Will it ever go back?", List.of()).request().instructions();
        assertThat(instructions).contains("the thing the USER asked about in their most recent",
                "not to something mentioned only in an earlier assistant answer", "never answer about a different card");
    }

    @Test
    void productionRewritesShortFollowUpsByDefaultButCanBeTurnedOff() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yaml"));
        assertThat(yaml.getObject().getProperty("boardgame.query-rewrite.enabled")).isEqualTo("${RAG_QUERY_REWRITE_ENABLED:true}");
        var base = new YamlPropertiesFactoryBean();
        base.setResources(new ClassPathResource("application.yaml"));
        assertThat(base.getObject().getProperty("boardgame.query-rewrite.enabled")).isEqualTo("${RAG_QUERY_REWRITE_ENABLED:false}");
    }
}
