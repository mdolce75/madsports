package com.rinkmetrics.dt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PartidoDtRepository extends JpaRepository<PartidoDt, Long> {

    /** Partidos del DT, los más recientes primero. */
    List<PartidoDt> findByUsuarioIdOrderByFechaDescIdDesc(Long usuarioId);

    Optional<PartidoDt> findByIdAndUsuarioId(Long id, Long usuarioId);

    /** El partido del equipo contra ese rival en esa fecha, si el DT ya lo empezó a cargar. */
    Optional<PartidoDt> findByUsuarioIdAndCategoriaAndRamaAndRivalAndFecha(Long usuarioId, String categoria, String rama,
                                                                       String rival, LocalDate fecha);

    /** Partidos que el DT empezó a cargar desde una fecha (para el cupo del mes). */
    long countByUsuarioIdAndCreadoGreaterThanEqual(Long usuarioId, Instant desde);
}
