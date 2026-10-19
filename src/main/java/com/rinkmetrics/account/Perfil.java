package com.rinkmetrics.account;

import com.rinkmetrics.sidgad.model.Federacion;

/**
 * Perfiles que puede tener un usuario: club, jugador o DT, de la FPP o de la AMP. A futuro se compran (un usuario puede
 * sumar varios); por ahora el registro ofrece uno, que excluye al resto.
 */
public enum Perfil {
    CLUBFPP, CLUBAMP, PLAYERFPP, PLAYERAMP, DTFPP, DTAMP;

    public boolean player() {
        return name().startsWith("PLAYER");
    }

    /** DT: carga estadísticas en vivo de sus equipos (Métricas DT). */
    public boolean dt() {
        return name().startsWith("DT");
    }

    /** Tipo de usuario de sus planes de suscripción: PLAYER, CLUB o DT. */
    public String tipoUsuario() {
        return player() ? "PLAYER" : dt() ? "DT" : "CLUB";
    }

    /** Federación cuyos datos usa el perfil: FPP o AMP. */
    public Federacion federacion() {
        return switch (this) {
            case CLUBFPP, PLAYERFPP, DTFPP -> Federacion.FPP;
            case CLUBAMP, PLAYERAMP, DTAMP -> Federacion.AMP;
        };
    }

    /** "Club FPP", "Jugador AMP", "DT FPP". */
    public String etiqueta() {
        String sigla = name().substring(name().length() - 3);
        return (player() ? "Jugador " : dt() ? "DT " : "Club ") + sigla;
    }
}
