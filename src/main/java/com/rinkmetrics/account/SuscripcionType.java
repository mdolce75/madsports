package com.rinkmetrics.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Tipo de suscripción (tabla {@code suscripcion_type}): para jugador (PLAYER_MENSUAL, PLAYER_TRIMESTRAL,
 * PLAYER_SEMESTRAL, PLAYER_ANUAL), para club (CLUB_*) o para DT (DT_*).
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
    /** PLAYER, CLUB o DT: a qué tipo de usuario corresponde ({@link Perfil#tipoUsuario()}). */
    private String tipoUsuario;
    /** Precio del plan completo, en dólares. */
    private BigDecimal precioUsd;
    /** Informes que incluye por mes. */
    private int informesPorMes;
    /** DT: equipos que puede tener (0 en los demás planes). */
    private int equiposMax;
    /** DT: partidos que puede empezar a cargar por mes (0 en los demás planes). */
    private int partidosPorMes;

    protected SuscripcionType() {
    }

    public SuscripcionType(String codigo, String nombre, int meses, String tipoUsuario, BigDecimal precioUsd,
                           int informesPorMes) {
        this.codigo = codigo;
        this.nombre = nombre;
        this.meses = meses;
        this.tipoUsuario = tipoUsuario;
        this.precioUsd = precioUsd;
        this.informesPorMes = informesPorMes;
    }

    /** Precio equivalente por mes, en dólares (redondeado a centavos). */
    public BigDecimal precioPorMes() {
        return precioUsd.divide(BigDecimal.valueOf(meses), 2, RoundingMode.HALF_UP);
    }

    /**
     * Cuánto más barato es este plan que pagar el mensual todos esos meses, en % entero (0 para el mensual).
     *
     * @param mensual el plan mensual del mismo tipo de usuario
     */
    public int ahorroPct(SuscripcionType mensual) {
        BigDecimal sinDescuento = mensual.precioUsd.multiply(BigDecimal.valueOf(meses));
        if (sinDescuento.signum() == 0) {
            return 0;
        }
        return BigDecimal.ONE.subtract(precioUsd.divide(sinDescuento, 6, RoundingMode.HALF_UP))
                .multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
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
        return desde.plusMonths(meses).minusDays(1);
    }

    public Long getId() { return id; }
    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
    public int getMeses() { return meses; }
    public String getTipoUsuario() { return tipoUsuario; }
    public BigDecimal getPrecioUsd() { return precioUsd; }
    public int getInformesPorMes() { return informesPorMes; }
    public int getEquiposMax() { return equiposMax; }
    public int getPartidosPorMes() { return partidosPorMes; }
}
