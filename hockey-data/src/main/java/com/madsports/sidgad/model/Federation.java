package com.madsports.sidgad.model;

import com.madsports.source.DataSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Federaciones relevadas, con su fuente de datos, sus categorías (de la más baja a la más alta) y en qué categorías
 * puede jugar un jugador según la suya.
 * <p>
 * FPP y AMP (SIDGAD, {@code id_fede_N} del catálogo): se puede jugar en la propia categoría y en las dos de arriba;
 * desde Juvenil se llega hasta la mayor (Primera / Senior), y Reserva y la mayor se juegan entre sí. Mendoza tiene más
 * categorías intermedias (Precadete, Cadete, Prejunior), así que dos categorías arriba es menos edad que en la FPP.
 * <p>
 * FSP (gestiondepsj, {@code federation_id} 1): cada división masculina (A1, A2, A3) tiene Primera y Segunda, que son
 * categorías distintas y se juegan entre sí (como Reserva y Primera); lo mismo Primera y Segunda femenino. La Copa
 * Nacional de Clubes se cuenta en Primera A1 masculino ({@link #cupCategory()}).
 */
public enum Federation {

    FPP(3, "Federación Porteña de Patín",
            List.of("INFANTIL", "PREJUVENIL", "JUVENIL", "JUNIORS", "RESERVA", "PRIMERA"),
            Map.of(
                    "INFANTIL", List.of("INFANTIL", "PREJUVENIL", "JUVENIL"),
                    "PREJUVENIL", List.of("PREJUVENIL", "JUVENIL", "JUNIORS"),
                    "JUVENIL", List.of("JUVENIL", "JUNIORS", "RESERVA", "PRIMERA"),
                    "JUNIORS", List.of("JUNIORS", "RESERVA", "PRIMERA"),
                    "RESERVA", List.of("RESERVA", "PRIMERA"),
                    "PRIMERA", List.of("RESERVA", "PRIMERA")), DataSource.SIDGAD),

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
                    "SENIOR", List.of("RESERVA", "SENIOR")), DataSource.SIDGAD),

    FSP(1, "Federación Sanjuanina de Patín",
            List.of("SEGUNDA A3", "PRIMERA A3", "SEGUNDA A2", "PRIMERA A2", "SEGUNDA A1", "PRIMERA A1", "SEGUNDA",
                    "PRIMERA"),
            Map.of(
                    "SEGUNDA A3", List.of("SEGUNDA A3", "PRIMERA A3"),
                    "PRIMERA A3", List.of("SEGUNDA A3", "PRIMERA A3"),
                    "SEGUNDA A2", List.of("SEGUNDA A2", "PRIMERA A2"),
                    "PRIMERA A2", List.of("SEGUNDA A2", "PRIMERA A2"),
                    "SEGUNDA A1", List.of("SEGUNDA A1", "PRIMERA A1"),
                    "PRIMERA A1", List.of("SEGUNDA A1", "PRIMERA A1"),
                    "SEGUNDA", List.of("SEGUNDA", "PRIMERA"),
                    "PRIMERA", List.of("SEGUNDA", "PRIMERA")), DataSource.PSJ);

    private final int id;
    private final String fullName;
    private final List<String> levels;
    private final Map<String, List<String>> allowed;
    private final DataSource source;

    Federation(int id, String name, List<String> levels, Map<String, List<String>> allowed, DataSource source) {
        this.id = id;
        this.fullName = name;
        this.levels = levels;
        this.allowed = allowed;
        this.source = source;
    }

    /** id de la federación en su fuente ({@code id_fede_N} en SIDGAD, {@code federation_id} en gestiondepsj). */
    public int id() {
        return id;
    }

    /** De dónde salen sus datos. */
    public DataSource source() {
        return source;
    }

    /**
     * La categoría en que se cuentan las copas interprovinciales de clubes (Copa Nacional): sus partidos no traen
     * categoría. Null si la federación no las releva.
     */
    public Competition.Category cupCategory() {
        return this == FSP ? new Competition.Category("PRIMERA A1", "MASCULINO") : null;
    }

    /** "Federación Porteña de Patín". La sigla es {@link #name()}. */
    public String fullName() {
        return fullName;
    }

    /** Categorías relevadas, de la más baja a la más alta (de Infantil para arriba). */
    public List<String> levels() {
        return levels;
    }

    /** Categorías en que puede jugar un jugador de esa categoría (vacía si no es de esta federación). */
    public List<String> allowed(String level) {
        return allowed.getOrDefault(level, List.of());
    }

    /** La federación de un id de su fuente, o null si no se releva. */
    public static Federation of(int id) {
        return Arrays.stream(values()).filter(f -> f.id == id).findFirst().orElse(null);
    }

    /** "FPP", "AMP" o "FSP" (sin distinguir mayúsculas); vacío o nulo = FPP. */
    public static Federation parse(String acronym) {
        if (acronym == null || acronym.isBlank()) {
            return FPP;
        }
        try {
            return valueOf(acronym.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Federación inválida: " + acronym + ". Válidas: "
                    + String.join(", ", Arrays.stream(values()).map(Enum::name).toList()) + ".");
        }
    }
}
