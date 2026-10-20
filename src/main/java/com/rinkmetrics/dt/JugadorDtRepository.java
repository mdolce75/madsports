package com.rinkmetrics.dt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JugadorDtRepository extends JpaRepository<JugadorDt, Long> {

    /** Jugadores activos del DT (de todos sus equipos). */
    List<JugadorDt> findByUsuarioIdAndActivoTrue(Long usuarioId);

    Optional<JugadorDt> findByUsuarioIdAndJugadorIdAndActivoTrue(Long usuarioId, int jugadorId);

    /** El jugador del DT con ese id, activo o dado de baja (para no duplicar a uno que vuelve desde SIDGAD). */
    Optional<JugadorDt> findByUsuarioIdAndJugadorId(Long usuarioId, int jugadorId);

    /** El jugador con el id más alto del DT (para numerar los que agrega). */
    Optional<JugadorDt> findTopByUsuarioIdOrderByJugadorIdDesc(Long usuarioId);
}
