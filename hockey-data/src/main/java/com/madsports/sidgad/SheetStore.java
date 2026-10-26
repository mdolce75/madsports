package com.madsports.sidgad;

import java.util.Optional;

/**
 * Las actas de partido ya leídas de SIDGAD, guardadas de forma permanente: un partido jugado no cambia, así que cada
 * acta se baja una vez y la usan todos los informes (de club y de jugador), también después de reiniciar la app. La
 * implementación decide cuándo conviene volver a bajarla (en los primeros días SIDGAD puede corregirla).
 */
public interface SheetStore {

    /** El acta guardada, si la hay y todavía sirve; vacío si hay que bajarla de nuevo. */
    Optional<String> get(int idp);

    /** Guarda (o actualiza) el acta de un partido. */
    void put(int idp, String html);
}
