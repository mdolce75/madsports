package com.rinkmetrics.dt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JugadorDtRepository extends JpaRepository<JugadorDt, Long> {

    /** Plantel del equipo del DT, también los dados de baja. */
    List<JugadorDt> findByUsuarioIdAndCategoriaAndRamaOrderByIdAsc(Long usuarioId, String categoria, String rama);

    Optional<JugadorDt> findByUsuarioIdAndCategoriaAndRamaAndJugadorId(Long usuarioId, String categoria, String rama,
                                                                      int jugadorId);

    /** El jugador con el id más alto del DT (para numerar los que agrega). */
    Optional<JugadorDt> findTopByUsuarioIdOrderByJugadorIdDesc(Long usuarioId);
}
