package com.rinkmetrics.dt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PartidoDtRepository extends JpaRepository<PartidoDt, Long> {

    /** Partidos del DT, los más recientes primero. */
    List<PartidoDt> findByUsuarioIdOrderByFechaDescIdDesc(Long usuarioId);

    Optional<PartidoDt> findByIdAndUsuarioId(Long id, Long usuarioId);

    /** El partido de SIDGAD de ese equipo, si ya se empezó a cargar. */
    Optional<PartidoDt> findByClubIdAndCategoriaAndRamaAndIdp(int clubId, String categoria, String rama, Integer idp);

    /** Partidos que el DT empezó a cargar desde una fecha (para el cupo del mes). */
    long countByUsuarioIdAndCreadoGreaterThanEqual(Long usuarioId, Instant desde);
}
