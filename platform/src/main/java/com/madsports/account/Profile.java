package com.madsports.account;

import com.madsports.sidgad.model.Federation;

/**
 * Perfiles que puede tener un usuario: club, jugador o DT, de la FPP o de la AMP. A futuro se compran (un usuario puede
 * sumar varios); por ahora el registro ofrece uno, que excluye al resto.
 */
public enum Profile {
    CLUBFPP, CLUBAMP, PLAYERFPP, PLAYERAMP, COACHFPP, COACHAMP;

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

    /** Federación cuyos datos usa el perfil: FPP o AMP. */
    public Federation federation() {
        return switch (this) {
            case CLUBFPP, PLAYERFPP, COACHFPP -> Federation.FPP;
            case CLUBAMP, PLAYERAMP, COACHAMP -> Federation.AMP;
        };
    }

    /** "Club FPP", "Jugador AMP", "DT FPP". */
    public String label() {
        String acronym = name().substring(name().length() - 3);
        return (player() ? "Jugador " : coach() ? "DT " : "Club ") + acronym;
    }
}
