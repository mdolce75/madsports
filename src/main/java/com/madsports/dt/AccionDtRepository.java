package com.madsports.dt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccionDtRepository extends JpaRepository<AccionDt, String> {

    /** Acciones del partido (también las borradas), en orden de juego. */
    List<AccionDt> findByPartidoIdOrderByTiempoAscSegundoAscCreadaAsc(Long partidoId);

    /** Las acciones de varios partidos (las de un equipo del DT), en orden de juego. */
    List<AccionDt> findByPartidoIdInOrderByPartidoIdAscTiempoAscSegundoAscCreadaAsc(java.util.Collection<Long> partidoIds);
}
