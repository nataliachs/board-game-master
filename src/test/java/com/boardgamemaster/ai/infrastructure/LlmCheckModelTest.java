package com.boardgamemaster.ai.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.boardgamemaster.ai.LlmClient;
import com.boardgamemaster.ai.QueryRewriteClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class LlmCheckModelTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmProperties answer = new LlmProperties("openai", "gpt-5.6-luna", "fixture-key", Duration.ofSeconds(60), 4096, "medium");

    private OpenAiLlmClient openAi(LlmProperties config) {
        return new OpenAiLlmClient(config, mapper, HttpClient.newHttpClient(), URI.create("http://localhost:1/unused"));
    }
    private static LlmProperties configOf(Object client) {
        return (LlmProperties) ReflectionTestUtils.getField(client, "config");
    }

    @Test void blankCheckModelKeepsTheAnswerClientForRewritingAndReview() {
        var checks = new LlmCheckProperties(" ", null);
        var answers = openAi(answer);
        assertThat(checks.separate()).isFalse();
        assertThat(checks.applyTo(answer)).isSameAs(answer);
        assertThat(LlmConfiguration.checkClient(answers, answer, checks, mapper)).isSameAs(answers);
    }

    @Test void checkModelGetsItsOwnClientSharingProviderKeyTimeoutAndOutputLimit() {
        var checks = new LlmCheckProperties("gpt-4.1-mini", "");
        Object client = LlmConfiguration.checkClient(openAi(answer), answer, checks, mapper);
        assertThat(client).isInstanceOf(OpenAiLlmClient.class).isInstanceOf(QueryRewriteClient.class);
        var config = configOf(client);
        assertThat(config.model()).isEqualTo("gpt-4.1-mini");
        assertThat(config.reasoningEffort()).isEqualTo("none");
        assertThat(config.provider()).isEqualTo("openai");
        assertThat(config.apiKey()).isEqualTo("fixture-key");
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.maxOutputTokens()).isEqualTo(4096);
        assertThat(configOf(openAi(answer)).model()).as("answers stay on LLM_MODEL").isEqualTo("gpt-5.6-luna");
    }

    @Test void nonOpenAiAnswerClientsAreNeverReplaced() {
        LlmClient disabled = request -> { throw new IllegalStateException(); };
        assertThat(LlmConfiguration.checkClient(disabled, answer, new LlmCheckProperties("gpt-4.1-mini", "none"), mapper))
                .isSameAs(disabled);
    }

    @Test void invalidCheckModelSettingsStopStartup() {
        assertThatThrownBy(() -> new LlmCheckProperties("gpt-4.1-mini", "low")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmCheckProperties("gpt-5.6-luna", "fast")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new LlmCheckProperties("gpt-5.6-luna", "low").reasoningEffort()).isEqualTo("low");
    }

    @Test void settingsBindFromConfiguration() {
        var context = new ApplicationContextRunner().withUserConfiguration(LlmConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("boardgame.llm.provider=openai", "boardgame.llm.api-key=fixture-key",
                        "boardgame.llm.model=gpt-5.6-luna", "boardgame.llm.reasoning-effort=medium",
                        "boardgame.llm.timeout=60s", "boardgame.llm.max-output-tokens=4096");
        context.withPropertyValues("boardgame.llm-checks.model=gpt-4.1-mini").run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app.getBean(LlmCheckProperties.class).model()).isEqualTo("gpt-4.1-mini");
            assertThat(app).hasSingleBean(LlmClient.class);
        });
        context.withPropertyValues("boardgame.llm-checks.model=gpt-4.1-mini", "boardgame.llm-checks.reasoning-effort=high")
                .run(app -> assertThat(app).hasFailed());
        context.run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app.getBean(LlmCheckProperties.class).separate()).isFalse();
        });
    }
}
