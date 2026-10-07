package com.boardgamemaster.game.domain;

final class CatalogValidation {
    private CatalogValidation() { }

    static String required(String value, int maxLength, String field, CatalogException.Code code) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new CatalogException(code, field + " is required and must be at most " + maxLength + " characters");
        }
        return value.strip();
    }

    static String optional(String value, int maxLength, String field, CatalogException.Code code) {
        if (value == null || value.isBlank()) { return null; }
        if (value.strip().length() > maxLength) {
            throw new CatalogException(code, field + " must be at most " + maxLength + " characters");
        }
        return value.strip();
    }
}
