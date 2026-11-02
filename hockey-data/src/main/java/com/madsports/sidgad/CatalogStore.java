package com.madsports.sidgad;

import java.time.Instant;
import java.util.Optional;

/**
 * La última copia bajada del catálogo de la fuente (competiciones con sus clubes y equipos, y las temporadas), guardada
 * de forma permanente: casi no cambia (un torneo nuevo, un equipo B que se suma), así que se baja una vez por día y no
 * en cada reinicio. Si la fuente falla, se sigue con la copia guardada.
 */
public interface CatalogStore {

    /** Una copia guardada y cuándo se bajó. */
    record Stored(String body, Instant fetchedAt) {
    }

    /** La copia guardada con esa clave ({@code sidgad:ls}, {@code sidgad:portal}), si la hay. */
    Optional<Stored> get(String key);

    /** Guarda (o reemplaza) la copia, con la hora de ahora. */
    void put(String key, String body);
}
