package com.boardgamemaster.ai;

/** Identifies a vector space. Different profiles must never be compared. */
public record EmbeddingProfile(String provider, String model, int dimensions) {
    public EmbeddingProfile {
        if (provider == null || !provider.matches("[a-z0-9-]{1,50}") || model == null || model.isBlank()
                || model.length() > 200 || model.codePoints().anyMatch(Character::isISOControl)
                || dimensions < 1 || dimensions > 2000) {
            throw new IllegalArgumentException("Invalid embedding profile");
        }
    }
}
