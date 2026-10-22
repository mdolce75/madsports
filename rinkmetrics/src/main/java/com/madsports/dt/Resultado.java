package com.madsports.dt;

/**
 * Resultado o zona de una acción: gol / no gol / atajado, campo propio / rival, falta cometida / recibida y color de
 * la tarjeta. Qué resultados admite cada acción lo dice {@link TipoAccion}.
 */
public enum Resultado {
    GOL, NO_GOL, ATAJADO, PROPIO, RIVAL, COMETIDA, RECIBIDA, AMARILLA, AZUL, ROJA
}
