package com.boardgamemaster.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenAPI boardGameMasterOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Board Game Master API")
                .version("v1")
                .description("Multi-game platform. Catalog, ingestion, embeddings, retrieval, grounded rule answers, persistent sessions, progressive teaching and guided setup are implemented. Voice and browser UI remain planned."));
    }
}
