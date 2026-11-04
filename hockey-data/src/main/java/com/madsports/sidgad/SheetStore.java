package com.madsports.sidgad;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Las actas de partido ya leídas de SIDGAD, guardadas de forma permanente: un partido jugado no cambia, así que cada
 * acta se baja una vez y la usan todos los informes (de club y de jugador), también después de reiniciar la app. La
 * sincronización vuelve a bajar las de los partidos recientes (SIDGAD puede corregirlas).
 */
public interface SheetStore {

    /** El acta guardada; vacío si no está. */
    Optional<String> get(int idp);

    /** Guarda (o actualiza) el acta de un partido. */
    void put(int idp, String html);

    /** Los partidos cuya acta se bajó (o se volvió a bajar) desde ese momento (para pasarlas a las tablas propias). */
    default List<Integer> changedSince(Instant since) {
        return List.of();
    }
}
