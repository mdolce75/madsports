package com.madsports.coach;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LiveMatchRepository extends JpaRepository<LiveMatch, Long> {

    /** Partidos del DT, los más recientes primero (con su equipo, para el nombre). */
    @EntityGraph(attributePaths = "team")
    List<LiveMatch> findByUserIdOrderByDateDescIdDesc(Long userId);

    @EntityGraph(attributePaths = "team")
    Optional<LiveMatch> findByIdAndUserId(Long id, Long userId);

    /** El partido del equipo contra ese rival en esa fecha, si el DT ya lo empezó a cargar. */
    @EntityGraph(attributePaths = "team")
    Optional<LiveMatch> findByUserIdAndTeamIdAndOpponentAndDate(Long userId, Long teamId, String opponent,
                                                                   LocalDate date);

    long countByTeamId(Long teamId);
}
