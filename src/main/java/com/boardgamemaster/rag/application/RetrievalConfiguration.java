package com.boardgamemaster.rag.application;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods = false)
public class RetrievalConfiguration {
    @Bean @ConditionalOnMissingBean(RuleReranker.class)
    RuleReranker cosineReranker() { return (query, candidates) -> java.util.List.copyOf(candidates); }
}
