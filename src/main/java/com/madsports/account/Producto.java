package com.madsports.account;

import java.util.Locale;

/**
 * Producto de Mad Sports al que pertenece una cuenta. Cada uno tiene su registro y su ingreso: una cuenta de
 * RinkReports (perfiles Club y Jugador) no ingresa a RinkMetrics (perfil DT) y al revés. El mismo email puede tener
 * una cuenta en cada producto.
 *
 * @param nombre    "RinkReports"
 * @param bajada    lo que va debajo del nombre en los mails
 * @param color     color principal de los mails (sobre fondo blanco), en hexadecimal sin "#"
 * @param inicio    portada del producto
 * @param ingreso   pantalla de ingreso
 */
public enum Producto {
    REPORTS("RinkReports", "Informes de hockey sobre patines", "1b8a68", "/rinkreports", "/rinkreports/login.html"),
    METRICS("RinkMetrics", "Hockey sobre patines", "0a7ea4", "/rinkmetrics", "/login.html");

    private final String nombre;
    private final String bajada;
    private final String color;
    private final String inicio;
    private final String ingreso;

    Producto(String nombre, String bajada, String color, String inicio, String ingreso) {
        this.nombre = nombre;
        this.bajada = bajada;
        this.color = color;
        this.inicio = inicio;
        this.ingreso = ingreso;
    }

    public String nombre() { return nombre; }
    public String bajada() { return bajada; }
    public String color() { return color; }
    public String inicio() { return inicio; }
    public String ingreso() { return ingreso; }

    /** El producto de un perfil: el DT es de RinkMetrics; Club y Jugador, de RinkReports. */
    public static Producto de(Perfil perfil) {
        return perfil.dt() ? METRICS : REPORTS;
    }

    /** "reports", "REPORTS" o "rinkreports"; null si viene vacío. */
    public static Producto parse(String value) {
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
