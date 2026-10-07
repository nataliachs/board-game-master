package com.boardgamemaster.rules.api;

import com.boardgamemaster.rules.domain.DocumentMetadata;
import com.boardgamemaster.rules.domain.RuleSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class RuleRequests {
    private RuleRequests() { }

    public record ImportMetadata(@NotBlank @Size(max = 200) String name,
            @NotNull RuleSourceType sourceType, @NotBlank @Size(max = 100) String version,
            @NotBlank @Size(max = 35) String language, @NotBlank @Size(max = 1000) String sourceReference) {
        DocumentMetadata toDomain() { return new DocumentMetadata(name, sourceType, version, language, sourceReference); }
    }
}
