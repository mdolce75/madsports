package com.rinkmetrics.dt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccionDtRepository extends JpaRepository<AccionDt, String> {

    /** Acciones del partido (también las borradas), en orden de juego. */
    List<AccionDt> findByPartidoIdOrderByTiempoAscSegundoAscCreadaAsc(Long partidoId);
}
