package com.madsports.coach;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamPlayerRepository extends JpaRepository<TeamPlayer, Long> {

    /** Jugadores activos del DT (de todos sus equipos). */
    List<TeamPlayer> findByUserIdAndActiveTrue(Long userId);

    Optional<TeamPlayer> findByUserIdAndPlayerIdAndActiveTrue(Long userId, int playerId);

    /** El jugador del DT con ese id, activo o dado de baja (para no duplicar a uno que vuelve desde SIDGAD). */
    Optional<TeamPlayer> findByUserIdAndPlayerId(Long userId, int playerId);

    /** El jugador con el id más alto del DT (para numerar los que agrega). */
    Optional<TeamPlayer> findTopByUserIdOrderByPlayerIdDesc(Long userId);
}
