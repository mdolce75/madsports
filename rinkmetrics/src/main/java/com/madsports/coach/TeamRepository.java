package com.madsports.coach;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    /** Equipos del DT, en el orden en que los creó. */
    List<Team> findByUserIdOrderByIdAsc(Long userId);

    Optional<Team> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);
}
