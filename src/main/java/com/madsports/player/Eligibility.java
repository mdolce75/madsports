package com.madsports.player;

import com.madsports.sidgad.model.Federacion;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Categorías en que puede jugar un jugador según su categoría y su federación ({@link Federacion#allowed}): puede
 * subir, nunca bajar. En la FPP:
 * <ul>
 *     <li>Infantil: Infantil, Prejuvenil y Juvenil</li>
 *     <li>Prejuvenil: Prejuvenil, Juvenil y Juniors</li>
 *     <li>Juvenil: Juvenil, Juniors, Reserva y Primera</li>
 *     <li>Juniors: Juniors, Reserva y Primera</li>
 *     <li>Reserva y Primera: Reserva y Primera</li>
 * </ul>
 * En la AMP la regla es la misma sobre sus categorías (Infantil, Precadete, Cadete, Prejuvenil, Juvenil, Prejunior,
 * Junior, Reserva y Senior).
 * <p>
 * La categoría del jugador es la más baja en que juega en el año (un Juvenil que también juega en Primera es
 * Juvenil). Como en el registro se lo elige de la planilla de cualquier equipo en que figura, el informe busca en
 * todas las categorías a las que podría pertenecer ({@link #searchLevels}) y después se queda con su categoría real.
 * Los métodos sin federación son de la FPP.
 */
public final class Eligibility {

    private Eligibility() {
    }

    /** Categorías habilitadas para un jugador de esa categoría; vacía si no se conoce (se revisan todas). */
    public static List<String> levels(Federacion fed, String level) {
        return level == null ? List.of() : fed.allowed(level.trim().toUpperCase());
    }

    public static List<String> levels(String level) {
        return levels(Federacion.FPP, level);
    }

    /**
     * Categorías a las que puede pertenecer alguien que juega en {@code level} (la regla al revés): quien juega en
     * Primera puede ser Juvenil, Juniors, Reserva o Primera.
     */
    static List<String> possibleBases(Federacion fed, String level) {
        String l = level.trim().toUpperCase();
        return fed.levels().stream().filter(b -> fed.allowed(b).contains(l)).toList();
    }

    static List<String> possibleBases(String level) {
        return possibleBases(Federacion.FPP, level);
    }

    /**
     * Dónde buscar a un jugador que figura en {@code level}: todas las categorías en que puede jugar, sea cual sea su
     * categoría real (quien figura en Primera puede ser Juvenil y jugar también en Juvenil y Juniors). Vacía si no se
     * conoce (se busca en todas).
     */
    public static List<String> searchLevels(Federacion fed, String level) {
        if (level == null || fed.allowed(level.trim().toUpperCase()).isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        possibleBases(fed, level).forEach(b -> out.addAll(fed.allowed(b)));
        return out.stream().sorted(Comparator.comparingInt(fed.levels()::indexOf)).toList();
    }

    public static List<String> searchLevels(String level) {
        return searchLevels(Federacion.FPP, level);
    }

    /** La categoría más baja (la categoría real de quien jugó en todas ellas), o null si no hay ninguna. */
    static String lowest(Federacion fed, Collection<String> levels) {
        List<String> order = fed.levels();
        return levels.stream().filter(order::contains).min(Comparator.comparingInt(order::indexOf)).orElse(null);
    }

    static String lowest(Collection<String> levels) {
        return lowest(Federacion.FPP, levels);
    }
}
