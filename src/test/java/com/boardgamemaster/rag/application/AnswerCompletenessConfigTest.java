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
    void productionRewritesShortFollowUpsByDefaultButCanBeTurnedOff() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yaml"));
        assertThat(yaml.getObject().getProperty("boardgame.query-rewrite.enabled")).isEqualTo("${RAG_QUERY_REWRITE_ENABLED:true}");
        var base = new YamlPropertiesFactoryBean();
        base.setResources(new ClassPathResource("application.yaml"));
        assertThat(base.getObject().getProperty("boardgame.query-rewrite.enabled")).isEqualTo("${RAG_QUERY_REWRITE_ENABLED:false}");
    }
}
