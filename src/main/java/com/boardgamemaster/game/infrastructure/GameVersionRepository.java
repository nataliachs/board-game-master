package com.boardgamemaster.game.infrastructure;

import com.boardgamemaster.game.domain.GameVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameVersionRepository extends JpaRepository<GameVersion, UUID> {
    Optional<GameVersion> findByIdAndGameId(UUID id, UUID gameId);
    Optional<GameVersion> findByGameIdAndDefaultVersionTrue(UUID gameId);
    List<GameVersion> findByGameIdOrderByEditionAscVersionLabelAscLanguageAsc(UUID gameId);
    boolean existsByGameIdAndEditionIgnoreCaseAndVersionLabelIgnoreCaseAndLanguage(
            UUID gameId, String edition, String versionLabel, String language);
}
