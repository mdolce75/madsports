package com.madsports.account;

import java.util.Locale;

/**
 * Producto de Mad Sports al que pertenece una cuenta. Cada uno tiene su registro y su ingreso: una cuenta de
 * RinkReports (perfiles Club y Jugador) no ingresa a RinkMetrics (perfil DT) y al revés. El mismo email y el mismo
 * usuario pueden tener una cuenta en cada producto.
 *
 * @param nombre    "RinkReports"
 * @param bajada    lo que va debajo del nombre en los mails
 * @param color     color principal de los mails (sobre fondo blanco), en hexadecimal sin "#"
 * @param inicio    portada del producto
 * @param ingreso   pantalla de ingreso
 */
public enum Product {
    REPORTS("RinkReports", "Informes de hockey sobre patines", "1b8a68", "/rinkreports", "/rinkreports/login.html"),
    METRICS("RinkMetrics", "Hockey sobre patines", "0a7ea4", "/rinkmetrics", "/rinkmetrics/login.html");

    private final String displayName;
    private final String tagline;
    private final String color;
    private final String home;
    private final String login;

    Product(String name, String tagline, String color, String home, String login) {
        this.displayName = name;
        this.tagline = tagline;
        this.color = color;
        this.home = home;
        this.login = login;
    }

    public String displayName() { return displayName; }
    public String tagline() { return tagline; }
    public String color() { return color; }
    public String home() { return home; }
    public String login() { return login; }

    /** El producto de un perfil: el DT es de RinkMetrics; Club y Jugador, de RinkReports. */
    public static Product de(Profile profile) {
        return profile.coach() ? METRICS : REPORTS;
    }

    /**
     * Nombre de la cuenta en la sesión ("REPORTS:juan"): el usuario solo no alcanza, porque el mismo puede tener una
     * cuenta en cada producto.
     */
    public String principal(String username) {
        return name() + ":" + username;
    }

    /** "reports", "REPORTS" o "rinkreports"; null si viene vacío. */
    public static Product parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT).replaceFirst("^RINK", "");
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Producto inválido: " + value + " (REPORTS o METRICS).");
        }
    }
}
