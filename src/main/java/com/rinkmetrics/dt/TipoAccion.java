package com.rinkmetrics.dt;

import java.util.Set;

/**
 * Acciones que carga el DT durante el partido, cada una con los resultados que admite (vacío = sin resultado) y si
 * suma un gol al equipo o al rival. Las del arco (atajada, gol recibido, libre y penal en contra) se cargan al
 * arquero en cancha.
 */
public enum TipoAccion {

    PERDIDA(Set.of(Resultado.PROPIO, Resultado.RIVAL), false),
    RECUPERACION(Set.of(Resultado.PROPIO, Resultado.RIVAL), false),
    TIRO(Set.of(Resultado.GOL, Resultado.NO_GOL), false),
    ATAJADA(Set.of(), true),
    GOL_RECIBIDO(Set.of(), true),
    LIBRE_FAVOR(Set.of(Resultado.GOL, Resultado.NO_GOL), false),
    LIBRE_CONTRA(Set.of(Resultado.GOL, Resultado.ATAJADO), true),
    PENAL_FAVOR(Set.of(Resultado.GOL, Resultado.NO_GOL), false),
    PENAL_CONTRA(Set.of(Resultado.GOL, Resultado.ATAJADO), true),
    FALTA(Set.of(Resultado.COMETIDA, Resultado.RECIBIDA), false),
    TARJETA(Set.of(Resultado.AMARILLA, Resultado.AZUL, Resultado.ROJA), false);

    private final Set<Resultado> resultados;
    private final boolean arquero;

    TipoAccion(Set<Resultado> resultados, boolean arquero) {
        this.resultados = resultados;
        this.arquero = arquero;
    }

    /** ¿Se carga al arquero en cancha? */
    public boolean arquero() {
        return arquero;
    }

    /** ¿El resultado corresponde a la acción? (null solo en las que no llevan resultado.) */
    public boolean admite(Resultado resultado) {
        return resultados.isEmpty() ? resultado == null : resultado != null && resultados.contains(resultado);
    }

    /** ¿Es un gol del equipo del DT? */
    public boolean golAFavor(Resultado resultado) {
        return resultado == Resultado.GOL && (this == TIRO || this == LIBRE_FAVOR || this == PENAL_FAVOR);
    }

    /** ¿Es un gol del rival? */
    public boolean golEnContra(Resultado resultado) {
        return this == GOL_RECIBIDO
                || resultado == Resultado.GOL && (this == LIBRE_CONTRA || this == PENAL_CONTRA);
    }
}
