package com.boardgamemaster.rules.domain;

import java.util.Locale;
import static com.boardgamemaster.rules.domain.RuleImportException.Code.INVALID_DOCUMENT;

/** Shared validation for HTTP and non-HTTP ingestion callers. */
public record DocumentMetadata(String name, RuleSourceType sourceType, String version,
        String language, String sourceReference) {
    public DocumentMetadata {
        name = required(name, 200, "Name");
        if (sourceType == null) { throw new RuleImportException(INVALID_DOCUMENT, "Source type is required"); }
        version = required(version, 100, "Document version");
        language = required(language, 35, "Language").toLowerCase(Locale.ROOT);
        if (!language.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) {
            throw new RuleImportException(INVALID_DOCUMENT, "Language must be a tag such as en or id");
        }
        sourceReference = required(sourceReference, 1000, "Source reference");
    }

    private static String required(String value, int max, String field) {
        if (value == null || value.isBlank() || value.strip().length() > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new RuleImportException(INVALID_DOCUMENT, field + " is required and must be at most " + max + " characters without controls");
        }
        return value.strip();
    }
}
