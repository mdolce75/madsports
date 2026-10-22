package com.madsports.coach;

/**
 * Resultado o zona de una acción: gol / no gol / atajado, campo propio / rival, falta cometida / recibida y color de
 * la tarjeta. Qué resultados admite cada acción lo dice {@link ActionType}.
 */
public enum Outcome {
    GOAL, NO_GOAL, SAVED, OWN_HALF, RIVAL_HALF, COMMITTED, RECEIVED, YELLOW, BLUE, RED
}
