package com.rinkmetrics.player;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Categorías en que puede jugar un jugador según su categoría (reglamento FPP): puede subir, nunca bajar.
 * <ul>
 *     <li>Infantil: Infantil, Prejuvenil y Juvenil</li>
 *     <li>Prejuvenil: Prejuvenil, Juvenil y Juniors</li>
 *     <li>Juvenil: Juvenil, Juniors, Reserva y Primera</li>
 *     <li>Juniors: Juniors, Reserva y Primera</li>
 *     <li>Reserva y Primera: Reserva y Primera</li>
 * </ul>
 * La categoría del jugador es la más baja en que juega en el año (un Juvenil que también juega en Primera es
 * Juvenil). Como en el registro se lo elige de la planilla de cualquier equipo en que figura, el informe busca en
 * todas las categorías a las que podría pertenecer ({@link #searchLevels}) y después se queda con su categoría real.
 */
public final class Eligibility {

    /** De la más baja a la más alta. */
    static final List<String> ORDER = List.of("INFANTIL", "PREJUVENIL", "JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");

    private static final Map<String, List<String>> ALLOWED = Map.of(
            "INFANTIL", List.of("INFANTIL", "PREJUVENIL", "JUVENIL"),
            "PREJUVENIL", List.of("PREJUVENIL", "JUVENIL", "JUNIORS"),
            "JUVENIL", List.of("JUVENIL", "JUNIORS", "RESERVA", "PRIMERA"),
            "JUNIORS", List.of("JUNIORS", "RESERVA", "PRIMERA"),
            "RESERVA", List.of("RESERVA", "PRIMERA"),
            "PRIMERA", List.of("RESERVA", "PRIMERA"));

    private Eligibility() {
    }

    /** Categorías habilitadas para un jugador de esa categoría; vacía si no se conoce (se revisan todas). */
    public static List<String> levels(String level) {
        return level == null ? List.of() : ALLOWED.getOrDefault(level.trim().toUpperCase(), List.of());
    }

    /**
     * Categorías a las que puede pertenecer alguien que juega en {@code level} (la regla al revés): quien juega en
     * Primera puede ser Juvenil, Juniors, Reserva o Primera.
     */
    static List<String> possibleBases(String level) {
        String l = level.trim().toUpperCase();
        return ORDER.stream().filter(b -> ALLOWED.get(b).contains(l)).toList();
    }

    /**
     * Dónde buscar a un jugador que figura en {@code level}: todas las categorías en que puede jugar, sea cual sea su
     * categoría real (quien figura en Primera puede ser Juvenil y jugar también en Juvenil y Juniors). Vacía si no se
     * conoce (se busca en todas).
     */
    public static List<String> searchLevels(String level) {
        if (level == null || !ALLOWED.containsKey(level.trim().toUpperCase())) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        possibleBases(level).forEach(b -> out.addAll(ALLOWED.get(b)));
        return out.stream().sorted(Comparator.comparingInt(ORDER::indexOf)).toList();
    }

    /** La categoría más baja (la categoría real de quien jugó en todas ellas), o null si no hay ninguna. */
    static String lowest(Collection<String> levels) {
        return levels.stream().filter(ORDER::contains).min(Comparator.comparingInt(ORDER::indexOf)).orElse(null);
    }
}
