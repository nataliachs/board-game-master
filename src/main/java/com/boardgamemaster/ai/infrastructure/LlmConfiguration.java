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
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient llmClient(LlmProperties config, ObjectMapper mapper) {
        if (config.provider().equals("disabled")) {
            return request -> { throw new LlmException(LlmException.Code.LLM_NOT_CONFIGURED,
                    "Enable and configure an LLM provider to generate rule answers"); };
        }
        return new OpenAiLlmClient(config, mapper,
                HttpClient.newBuilder().connectTimeout(config.timeout()).followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://api.openai.com/v1/responses"));
    }
}
