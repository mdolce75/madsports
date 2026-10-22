package com.madsports.coach;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface StartedMatchRepository extends JpaRepository<StartedMatch, Long> {

    /** Partidos que el DT empezó desde una fecha (para el cupo del mes), también los que después eliminó. */
    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant from);
}
