package com.boardgamemaster.ai;

import java.util.List;

public record Embedding(List<Float> values) {
    public Embedding {
        if (values == null || values.isEmpty() || values.size() > 2000) { throw invalid(); }
        double norm = 0;
        for (Float value : values) {
            if (value == null || !Float.isFinite(value)) { throw invalid(); }
            norm += (double) value * value;
        }
        if (norm == 0) { throw invalid(); }
        // Cosine ranking is scale-independent. Unit normalization avoids float accumulation
        // overflow/underflow inside vector operators for extreme but finite provider values.
        double length = Math.sqrt(norm);
        values = values.stream().map(value -> (float) (value / length)).toList();
    }

    public void requireDimensions(int expected) {
        if (values.size() != expected) { throw invalid(); }
    }

    private static EmbeddingException invalid() {
        return new EmbeddingException(EmbeddingException.Code.EMBEDDING_INVALID_RESPONSE,
                "Embedding response must contain a finite, nonzero vector of the configured dimensions");
    }
}
