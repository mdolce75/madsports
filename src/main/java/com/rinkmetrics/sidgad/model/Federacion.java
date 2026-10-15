package com.rinkmetrics.sidgad.model;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Federaciones relevadas en SIDGAD ({@code id_fede_N} del catálogo), con sus categorías de Infantil para arriba
 * (de la más baja a la más alta) y en qué categorías puede jugar un jugador según la suya.
 * <p>
 * La regla es la misma en las dos: se puede jugar en la propia categoría y en las dos de arriba; desde Juvenil se
 * llega hasta la mayor (Primera / Senior), y Reserva y la mayor se juegan entre sí. Mendoza tiene más categorías
 * intermedias (Precadete, Cadete, Prejunior), así que dos categorías arriba es menos edad que en la FPP.
 */
public enum Federacion {

    FPP(3, "Federación Porteña de Patín",
            List.of("INFANTIL", "PREJUVENIL", "JUVENIL", "JUNIORS", "RESERVA", "PRIMERA"),
            Map.of(
                    "INFANTIL", List.of("INFANTIL", "PREJUVENIL", "JUVENIL"),
                    "PREJUVENIL", List.of("PREJUVENIL", "JUVENIL", "JUNIORS"),
                    "JUVENIL", List.of("JUVENIL", "JUNIORS", "RESERVA", "PRIMERA"),
                    "JUNIORS", List.of("JUNIORS", "RESERVA", "PRIMERA"),
                    "RESERVA", List.of("RESERVA", "PRIMERA"),
                    "PRIMERA", List.of("RESERVA", "PRIMERA"))),

    AMP(4, "Asociación Mendocina de Patín",
            List.of("INFANTIL", "PRECADETE", "CADETE", "PREJUVENIL", "JUVENIL", "PREJUNIOR", "JUNIOR", "RESERVA",
                    "SENIOR"),
            Map.of(
                    "INFANTIL", List.of("INFANTIL", "PRECADETE", "CADETE"),
                    "PRECADETE", List.of("PRECADETE", "CADETE", "PREJUVENIL"),
                    "CADETE", List.of("CADETE", "PREJUVENIL", "JUVENIL"),
                    "PREJUVENIL", List.of("PREJUVENIL", "JUVENIL", "PREJUNIOR"),
                    "JUVENIL", List.of("JUVENIL", "PREJUNIOR", "JUNIOR", "RESERVA", "SENIOR"),
                    "PREJUNIOR", List.of("PREJUNIOR", "JUNIOR", "RESERVA", "SENIOR"),
                    "JUNIOR", List.of("JUNIOR", "RESERVA", "SENIOR"),
                    "RESERVA", List.of("RESERVA", "SENIOR"),
                    "SENIOR", List.of("RESERVA", "SENIOR")));

    private final int id;
    private final String nombre;
    private final List<String> levels;
    private final Map<String, List<String>> allowed;

    Federacion(int id, String nombre, List<String> levels, Map<String, List<String>> allowed) {
        this.id = id;
        this.nombre = nombre;
        this.levels = levels;
        this.allowed = allowed;
    }

    /** id de la federación en SIDGAD ({@code id_fede_N}). */
    public int id() {
        return id;
    }

    /** "Federación Porteña de Patín". La sigla es {@link #name()}. */
    public String nombre() {
        return nombre;
    }

    /** Categorías relevadas, de la más baja a la más alta (de Infantil para arriba). */
    public List<String> levels() {
        return levels;
    }

    /** Categorías en que puede jugar un jugador de esa categoría (vacía si no es de esta federación). */
    public List<String> allowed(String level) {
        return allowed.getOrDefault(level, List.of());
    }

    /** La federación de un id de SIDGAD, o null si no se releva. */
    public static Federacion of(int id) {
        return Arrays.stream(values()).filter(f -> f.id == id).findFirst().orElse(null);
    }

    /** "FPP" o "AMP" (sin distinguir mayúsculas); vacío o nulo = FPP. */
    public static Federacion parse(String sigla) {
        if (sigla == null || sigla.isBlank()) {
            return FPP;
        }
        try {
            return valueOf(sigla.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Federación inválida: " + sigla + ". Válidas: FPP, AMP.");
        }
    }
}
