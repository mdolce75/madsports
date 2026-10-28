package com.madsports.account;

import com.madsports.sidgad.model.Federation;

/**
 * Perfiles que puede tener un usuario: club, jugador o DT, de la FPP o de la AMP. A futuro se compran (un usuario puede
 * sumar varios); por ahora el registro ofrece uno, que excluye al resto. {@link #COACHOTR} es el DT de otra federación
 * o liga, sin datos en SIDGAD: escribe el nombre de su club y carga a mano sus equipos y jugadores.
 */
public enum Profile {
    CLUBFPP, CLUBAMP, PLAYERFPP, PLAYERAMP, COACHFPP, COACHAMP, COACHOTR;

    public boolean player() {
        return name().startsWith("PLAYER");
    }

    /** DT: carga estadísticas en vivo de sus equipos (RinkMetrics). */
    public boolean coach() {
        return name().startsWith("COACH");
    }

    /** Tipo de usuario de sus planes de suscripción: PLAYER, CLUB o DT. */
    public String userType() {
        return player() ? "PLAYER" : coach() ? "COACH" : "CLUB";
    }

    /** Federación cuyos datos usa el perfil: FPP o AMP; null para el de otra federación (sin datos en SIDGAD). */
    public Federation federation() {
        return switch (this) {
            case CLUBFPP, PLAYERFPP, COACHFPP -> Federation.FPP;
            case CLUBAMP, PLAYERAMP, COACHAMP -> Federation.AMP;
            case COACHOTR -> null;
        };
    }

    /** ¿Sus datos salen de SIDGAD (FPP o AMP)? */
    public boolean sidgad() {
        return federation() != null;
    }

    /** La federación como la ven la sesión y los partidos: "FPP", "AMP" u "OTR" (otra). */
    public String federationCode() {
        return name().substring(name().length() - 3);
    }

    /** "Club FPP", "Jugador AMP", "DT FPP", "DT Otra". */
    public String label() {
        String acronym = sidgad() ? federationCode() : "Otra";
        return (player() ? "Jugador " : coach() ? "DT " : "Club ") + acronym;
    }
}
