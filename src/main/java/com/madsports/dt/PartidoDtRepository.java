package com.madsports.dt;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PartidoDtRepository extends JpaRepository<PartidoDt, Long> {

    /** Partidos del DT, los más recientes primero (con su equipo, para el nombre). */
    @EntityGraph(attributePaths = "equipo")
    List<PartidoDt> findByUsuarioIdOrderByFechaDescIdDesc(Long usuarioId);

    @EntityGraph(attributePaths = "equipo")
    Optional<PartidoDt> findByIdAndUsuarioId(Long id, Long usuarioId);

    /** El partido del equipo contra ese rival en esa fecha, si el DT ya lo empezó a cargar. */
    @EntityGraph(attributePaths = "equipo")
    Optional<PartidoDt> findByUsuarioIdAndEquipoIdAndRivalAndFecha(Long usuarioId, Long equipoId, String rival,
                                                                   LocalDate fecha);

    long countByEquipoId(Long equipoId);
}
