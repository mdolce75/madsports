package com.madsports.account;

import com.madsports.sidgad.model.Federation;

import java.util.EnumSet;
import java.util.Set;

/**
 * Perfiles que puede tener un usuario. {@link #REPORTS} es el usuario de RinkReports: sin club ni federación propios,
 * pide informes de equipo o de jugador de cualquier club de cualquier federación (el registro de RinkReports da solo
 * este). Las cuentas de antes de RinkReports conservan el suyo: club o jugador, de la FPP o de la AMP. En RinkMetrics,
 * el DT de la FPP o de la AMP, o {@link #COACHOTR}, el DT de otra federación o liga, sin datos en SIDGAD: escribe el
 * nombre de su club y carga a mano sus equipos y jugadores.
 */
public enum Profile {
    CLUBFPP, CLUBAMP, PLAYERFPP, PLAYERAMP, COACHFPP, COACHAMP, COACHOTR, REPORTS;

    /** Los perfiles de RinkReports: el usuario de ahora y los de club y jugador de antes. */
    public static final Set<Profile> RINKREPORTS = EnumSet.of(CLUBFPP, CLUBAMP, PLAYERFPP, PLAYERAMP, REPORTS);

    public boolean player() {
        return name().startsWith("PLAYER");
    }

    /** DT: carga estadísticas en vivo de sus equipos (RinkMetrics). */
    public boolean coach() {
        return name().startsWith("COACH");
    }

    /** Tipo de usuario de sus planes de suscripción: PLAYER, CLUB, COACH o REPORTS. */
    public String userType() {
        return this == REPORTS ? "REPORTS" : player() ? "PLAYER" : coach() ? "COACH" : "CLUB";
    }

    /**
     * Federación cuyos datos usa el perfil: FPP o AMP; null para el de otra federación (sin datos en SIDGAD) y para el
     * usuario de RinkReports, que elige la federación en cada informe.
     */
    public Federation federation() {
        return switch (this) {
            case CLUBFPP, PLAYERFPP, COACHFPP -> Federation.FPP;
            case CLUBAMP, PLAYERAMP, COACHAMP -> Federation.AMP;
            case COACHOTR, REPORTS -> null;
        };
    }

    /** ¿Sus datos salen de SIDGAD? Todos menos el DT de otra federación. */
    public boolean sidgad() {
        return this != COACHOTR;
    }

    /** La federación como la ven la sesión y los partidos: "FPP", "AMP" u "OTR" (otra). */
    public String federationCode() {
        return name().substring(name().length() - 3);
    }

    /** "Club FPP", "Jugador AMP", "DT FPP", "DT Otra", "RinkReports". */
    public String label() {
        if (this == REPORTS) {
            return "RinkReports";
        }
        String acronym = sidgad() ? federationCode() : "Otra";
        return (player() ? "Jugador " : coach() ? "DT " : "Club ") + acronym;
    }
}
