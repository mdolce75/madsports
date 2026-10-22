package com.madsports.coach;

/**
 * Estado de un partido de la planilla: creado sin iniciar, en juego (el reloj corre o está en pausa), en el
 * entretiempo, terminado (el reloj del 2T llegó a 00:00) o cerrado (ya no acepta acciones).
 */
public enum MatchStatus {
    PENDING, IN_PLAY, HALF_TIME, FINISHED, CLOSED
}
