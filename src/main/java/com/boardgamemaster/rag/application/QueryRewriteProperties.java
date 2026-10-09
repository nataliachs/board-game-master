package com.boardgamemaster.rag.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boardgame.query-rewrite")
public record QueryRewriteProperties(boolean enabled) { }
