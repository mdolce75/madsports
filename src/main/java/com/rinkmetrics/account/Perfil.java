package com.rinkmetrics.account;

import com.rinkmetrics.sidgad.model.Federacion;

/**
 * Perfiles que puede tener un usuario. A futuro se compran (un usuario puede sumar varios); por ahora el registro
 * ofrece club o jugador de la FPP o de la AMP, y uno excluye al resto.
 */
public enum Perfil {
    CLUBFPP, CLUBFSP, CLUBCNT, CLUBAMP, PLAYERFPP, PLAYERFSP, PLAYERCNT, PLAYERAMP;

    /** ¿Se puede elegir en la pantalla de registro? */
    public boolean registrable() {
        return federacion() != null;
    }

    public boolean player() {
        return name().startsWith("PLAYER");
    }

    /** Federación cuyos datos usa el perfil (FPP o AMP); null si todavía no se releva (FSP, CNT). */
    public Federacion federacion() {
        return switch (this) {
            case CLUBFPP, PLAYERFPP -> Federacion.FPP;
            case CLUBAMP, PLAYERAMP -> Federacion.AMP;
            default -> null;
        };
    }

    /** "Club FPP", "Jugador AMP". */
    public String etiqueta() {
        return (player() ? "Jugador " : "Club ") + name().substring(player() ? 6 : 4);
    }
}
