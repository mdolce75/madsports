package com.madsports.dt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface PartidoCreadoRepository extends JpaRepository<PartidoCreado, Long> {

    /** Partidos que el DT empezó desde una fecha (para el cupo del mes), también los que después eliminó. */
    long countByUsuarioIdAndCreadoGreaterThanEqual(Long usuarioId, Instant desde);
}
