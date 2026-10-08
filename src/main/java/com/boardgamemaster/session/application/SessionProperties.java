package com.boardgamemaster.session.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("boardgame.session")
public record SessionProperties(Integer recentMessageLimit, Integer contextCharacters) {
    public SessionProperties {
        recentMessageLimit = recentMessageLimit == null ? 8 : recentMessageLimit;
        contextCharacters = contextCharacters == null ? 8000 : contextCharacters;
        if (recentMessageLimit < 2 || recentMessageLimit > 50 || contextCharacters < 1000 || contextCharacters > 16000) {
            throw new IllegalArgumentException("Invalid session context limits");
        }
    }
}
