package com.rinkmetrics.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Plan de suscripción mensual (tabla {@code suscripcion_type}): PLAYER_MENSUAL, CLUB_MENSUAL, y para el DT,
 * DT_FREE (sin costo, 1 equipo y 6 partidos por mes) o DT_MENSUAL.
 */
@Entity
@Table(name = "suscripcion_type")
public class SuscripcionType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PLAYER_MENSUAL, CLUB_MENSUAL, DT_FREE o DT_MENSUAL. */
    private String codigo;
    private String nombre;
    /** PLAYER, CLUB o DT: a qué tipo de usuario corresponde ({@link Perfil#tipoUsuario()}). */
    private String tipoUsuario;
    /** Precio por mes, en dólares (se muestra en pesos: ver Cotizacion). */
    private BigDecimal precioUsd;
    /** Informes que incluye por mes. */
    private int informesPorMes;
    /** DT: equipos que puede tener (0 en los demás planes). */
    private int equiposMax;
    /** DT: partidos que puede empezar a cargar por mes (0 en los demás planes). */
    private int partidosPorMes;

    protected SuscripcionType() {
    }

    public SuscripcionType(String codigo, String nombre, String tipoUsuario, BigDecimal precioUsd, int informesPorMes) {
        this.codigo = codigo;
        this.nombre = nombre;
        this.tipoUsuario = tipoUsuario;
        this.precioUsd = precioUsd;
        this.informesPorMes = informesPorMes;
    }

    /** ¿Es para jugadores (PLAYER_*)? */
    public boolean paraJugador() {
        return "PLAYER".equals(tipoUsuario);
    }

    /** ¿Es un plan del perfil? (PLAYER_* para jugadores, CLUB_* para clubes, DT_* para DT.) */
    public boolean paraPerfil(Perfil perfil) {
        return perfil.tipoUsuario().equals(tipoUsuario);
    }

    /** "jugadores", "clubes" o "DT", para los mensajes. */
    public String destinatarios() {
        return switch (tipoUsuario) {
            case "PLAYER" -> "jugadores";
            case "DT" -> "DT";
            default -> "clubes";
        };
    }

    /** Último día de una suscripción de este tipo que empieza en {@code desde}. */
    public LocalDate hasta(LocalDate desde) {
        return desde.plusMonths(1).minusDays(1);
    }

    public Long getId() { return id; }
    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
    public String getTipoUsuario() { return tipoUsuario; }
    public BigDecimal getPrecioUsd() { return precioUsd; }
    public int getInformesPorMes() { return informesPorMes; }
    public int getEquiposMax() { return equiposMax; }
    public int getPartidosPorMes() { return partidosPorMes; }
}
