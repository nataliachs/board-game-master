package com.boardgamemaster.game.domain;

public class CatalogException extends RuntimeException {
    public enum Code {
        INVALID_GAME, INVALID_GAME_VERSION, INVALID_PLAYER_COUNT,
        GAME_NOT_FOUND, GAME_VERSION_NOT_FOUND, GAME_SLUG_EXISTS, GAME_VERSION_EXISTS,
        GAME_INACTIVE, GAME_VERSION_INACTIVE, DEFAULT_VERSION_NOT_CONFIGURED, INVALID_PAGINATION
    }

    private final Code code;

    public CatalogException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
