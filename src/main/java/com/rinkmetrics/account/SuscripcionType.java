package com.rinkmetrics.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Tipo de suscripción (tabla {@code suscripcion_type}): para jugador (PLAYER_MENSUAL, PLAYER_TRIMESTRAL,
 * PLAYER_SEMESTRAL, PLAYER_ANUAL) o para club (CLUB_MENSUAL, CLUB_TRIMESTRAL, CLUB_SEMESTRAL, CLUB_ANUAL).
 */
@Entity
@Table(name = "suscripcion_type")
public class SuscripcionType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PLAYER_MENSUAL ... CLUB_ANUAL. */
    private String codigo;
    private String nombre;
    /** Duración en meses (1, 3, 6 o 12). */
    private int meses;
    /** PLAYER o CLUB: a qué tipo de usuario corresponde. */
    private String tipoUsuario;

    protected SuscripcionType() {
    }

    public SuscripcionType(String codigo, String nombre, int meses, String tipoUsuario) {
        this.codigo = codigo;
        this.nombre = nombre;
        this.meses = meses;
        this.tipoUsuario = tipoUsuario;
    }

    /** ¿Es para jugadores (PLAYER_*)? Si no, es para clubes (CLUB_*). */
    public boolean paraJugador() {
        return "PLAYER".equals(tipoUsuario);
    }

    /** Último día de una suscripción de este tipo que empieza en {@code desde}. */
    public LocalDate hasta(LocalDate desde) {
        return desde.plusMonths(meses).minusDays(1);
    }

    public Long getId() { return id; }
    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
    public int getMeses() { return meses; }
    public String getTipoUsuario() { return tipoUsuario; }
}
