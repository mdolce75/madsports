package com.madsports.sidgad;

import java.time.Instant;
import java.util.Optional;

/**
 * Las copias guardadas de lo bajado de la fuente: el catálogo (competiciones con sus clubes y equipos, temporadas) y los
 * datos de cada competición (tabla, calendario, estadísticas por jugador) y de cada jugador (ficha de temporada). Los
 * informes leen de acá y la fuente se consulta en la sincronización programada; si la fuente falla, se sigue con la
 * copia guardada. Se guarda lo bajado tal cual (HTML): si se corrige un parser, se reprocesa sin volver a pedirlo.
 */
public interface SnapshotStore {

    /**
     * Una copia guardada.
     *
     * @param fetchedAt cuándo se bajó por última vez
     * @param changedAt cuándo cambió su contenido por última vez
     */
    record Stored(String body, Instant fetchedAt, Instant changedAt) {

        public Stored(String body, Instant fetchedAt) {
            this(body, fetchedAt, fetchedAt);
        }
    }

    /** La copia guardada con esa clave ({@code sidgad:ls}, {@code sidgad:calendar:779}), si la hay. */
    Optional<Stored> get(String key);

    /**
     * Guarda (o reemplaza) la copia, con la hora de ahora.
     *
     * @return true si es nueva o su contenido cambió
     */
    boolean put(String key, String body);
}
