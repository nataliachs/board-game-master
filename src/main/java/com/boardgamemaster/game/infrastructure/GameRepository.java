package com.boardgamemaster.game.infrastructure;

import com.boardgamemaster.game.domain.Game;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameRepository extends JpaRepository<Game, UUID> {
    boolean existsBySlug(String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from Game game where game.id = :id")
    Optional<Game> findByIdForUpdate(@Param("id") UUID id);
}
