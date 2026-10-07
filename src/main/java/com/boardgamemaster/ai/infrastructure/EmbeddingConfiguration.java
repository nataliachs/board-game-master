package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfiguration {
    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    EmbeddingClient embeddingClient(EmbeddingProperties config, ObjectMapper mapper) {
        if (config.provider().equals("disabled")) {
            return new EmbeddingClient() {
                public EmbeddingProfile profile() { return new EmbeddingProfile("disabled", config.model(), config.dimensions()); }
                public Embedding embed(String text) {
                    throw new EmbeddingException(EmbeddingException.Code.EMBEDDING_NOT_CONFIGURED,
                            "Enable and configure an embedding provider to index documents or search rules");
                }
            };
        }
        return new OpenAiEmbeddingClient(config, mapper,
                HttpClient.newBuilder().connectTimeout(config.timeout()).followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://api.openai.com/v1/embeddings"));
    }
}
