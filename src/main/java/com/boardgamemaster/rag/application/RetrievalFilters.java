package com.boardgamemaster.rag.application;

import com.boardgamemaster.rules.domain.RuleSourceType;
import java.util.*;

/** Expansion selection uses owned EXPANSION documents; it introduces no session/expansion domain. */
public record RetrievalFilters(String language, Set<RuleSourceType> sourceTypes, Set<UUID> documentIds,
        Set<UUID> expansionDocumentIds) {
    public RetrievalFilters {
        if (language != null) {
            language = language.strip().toLowerCase(Locale.ROOT);
            if (!language.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) { throw invalid(); }
        }
        sourceTypes = copy(sourceTypes);
        documentIds = copy(documentIds);
        expansionDocumentIds = copy(expansionDocumentIds);
    }
    private static <T> Set<T> copy(Set<T> values) {
        if (values == null) { return Set.of(); }
        if (values.size() > 50 || values.stream().anyMatch(Objects::isNull)) { throw invalid(); }
        return Set.copyOf(values);
    }
    public static RetrievalFilters none() { return new RetrievalFilters(null, null, null, null); }
    public RetrievalFilters withDefaultLanguage(String defaultLanguage) {
        return new RetrievalFilters(language == null ? defaultLanguage : language, sourceTypes, documentIds, expansionDocumentIds);
    }
    private static RetrievalException invalid() {
        return new RetrievalException(RetrievalException.Code.INVALID_SEARCH, "Invalid retrieval filters; lists are limited to 50 and language must be a tag such as en or en-US");
    }
}
