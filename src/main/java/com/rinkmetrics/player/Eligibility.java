package com.rinkmetrics.player;

import java.util.List;
import java.util.Map;

/**
 * Categorías en que puede jugar un jugador según su categoría (reglamento FPP): puede subir, nunca bajar.
 * <ul>
 *     <li>Infantil: Infantil, Prejuvenil y Juvenil</li>
 *     <li>Prejuvenil: Prejuvenil, Juvenil y Juniors</li>
 *     <li>Juvenil: Juvenil, Juniors, Reserva y Primera</li>
 *     <li>Juniors: Juniors, Reserva y Primera</li>
 *     <li>Reserva y Primera: Reserva y Primera</li>
 * </ul>
 * El informe del jugador lee solo las actas de esas categorías (en su rama).
 */
public final class Eligibility {

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
}
