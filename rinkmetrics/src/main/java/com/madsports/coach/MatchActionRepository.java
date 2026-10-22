package com.madsports.coach;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchActionRepository extends JpaRepository<MatchAction, String> {

    /** Acciones del partido (también las borradas), en orden de juego. */
    List<MatchAction> findByMatchIdOrderByPeriodAscAtSecondAscCreatedAtAsc(Long matchId);

    /** Las acciones de varios partidos (las de un equipo del DT), en orden de juego. */
    List<MatchAction> findByMatchIdInOrderByMatchIdAscPeriodAscAtSecondAscCreatedAtAsc(java.util.Collection<Long> matchIds);
}
