package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LlmProperties.class, GameDraftProperties.class, LlmCheckProperties.class})
public class LlmConfiguration {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LlmConfiguration.class);

    /**
     * The client for rewriting and evidence review: a second OpenAI client with {@code LLM_CHECK_MODEL}
     * when that is set and the answer client is the OpenAI one, otherwise the answer client itself.
     */
    static Object checkClient(LlmClient answers, LlmProperties config, LlmCheckProperties checks, ObjectMapper mapper) {
        if (!checks.separate() || !(answers instanceof OpenAiLlmClient)) { return answers; }
        return openAi(checks.applyTo(config), GameDraftProperties.defaults(), mapper);
    }
    private static OpenAiLlmClient openAi(LlmProperties config, GameDraftProperties drafts, ObjectMapper mapper) {
        return new OpenAiLlmClient(config, drafts, mapper,
                HttpClient.newBuilder().connectTimeout(config.timeout()).followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://api.openai.com/v1/responses"));
    }

    @Bean @Primary @ConditionalOnMissingBean(GameDraftClient.class)
    GameDraftClient gameDraftClient(LlmClient client) {
        if (client instanceof GameDraftClient drafts) { return drafts::draftGame; }
        return request -> { throw new LlmException(LlmException.Code.LLM_NOT_CONFIGURED,
                "Enable and configure an LLM provider to import a game from a rulebook"); };
    }
    // The OpenAI LlmClient also implements these ports at runtime. Prefer each explicit port wrapper.
    @Bean @Primary @ConditionalOnMissingBean(EvidenceReviewClient.class)
    EvidenceReviewClient evidenceReviewClient(LlmClient answers, LlmProperties config, LlmCheckProperties checks, ObjectMapper mapper) {
        Object client = checkClient(answers, config, checks, mapper);
        LOG.info("Query rewriting and evidence review use model={}, reasoningEffort={}",
                client == answers ? config.model() : checks.model(), client == answers ? config.reasoningEffort() : checks.reasoningEffort());
        return new EvidenceReviewClient() {
            public java.util.List<java.util.UUID> selectEvidence(LlmRequest request) {
                if (client instanceof EvidenceReviewClient review) { return review.selectEvidence(request); }
                throw unavailable();
            }
            public boolean supportsAnswer(LlmRequest request) {
                if (client instanceof EvidenceReviewClient review) { return review.supportsAnswer(request); }
                throw unavailable();
            }
            private LlmException unavailable() {
                return new LlmException(LlmException.Code.LLM_NOT_CONFIGURED,
                        "The configured LLM provider does not support evidence review");
            }
        };
    }
    @Bean @Primary @ConditionalOnMissingBean(QueryRewriteClient.class)
    QueryRewriteClient queryRewriteClient(LlmClient answers, LlmProperties config, LlmCheckProperties checks, ObjectMapper mapper) {
        Object client = checkClient(answers, config, checks, mapper);
        if (client instanceof QueryRewriteClient rewriter) { return rewriter::rewrite; }
        return request -> { throw new LlmException(LlmException.Code.LLM_NOT_CONFIGURED,
                "The configured LLM provider does not support query rewriting"); };
    }
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient llmClient(LlmProperties config, GameDraftProperties drafts, ObjectMapper mapper) {
        if (config.provider().equals("disabled")) {
            return request -> { throw new LlmException(LlmException.Code.LLM_NOT_CONFIGURED,
                    "Enable and configure an LLM provider to generate rule answers"); };
        }
        return openAi(config, drafts, mapper);
    }
}
