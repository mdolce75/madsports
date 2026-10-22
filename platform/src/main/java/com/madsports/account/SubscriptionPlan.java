package com.madsports.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Plan de suscripción mensual (tabla {@code suscripcion_type}): PLAYER_MENSUAL, CLUB_MENSUAL, y para el DT,
 * DT_FREE (sin costo, 1 equipo y partidos sin límite, hasta el 31/12/2026) o DT_MENSUAL.
 */
@Entity
@Table(name = "subscription_plan")
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PLAYER_MENSUAL, CLUB_MENSUAL, DT_FREE o DT_MENSUAL. */
    private String code;
    private String name;
    /** PLAYER, CLUB o DT: a qué tipo de usuario corresponde ({@link Profile#userType()}). */
    private String userType;
    /** Precio por mes, en dólares (se muestra en pesos: ver Cotizacion). */
    private BigDecimal priceUsd;
    /** Informes que incluye por mes. */
    private int reportsPerMonth;
    /** DT: equipos que puede tener (0 en los demás planes). */
    private int maxTeams;
    /** DT: partidos que puede empezar a cargar por mes; 0 = sin límite (y en los planes que no son de DT). */
    private int matchesPerMonth;
    /** Último día de un plan con fecha fija de fin (el DT free); null en los mensuales, que se renuevan. */
    private LocalDate validUntil;

    protected SubscriptionPlan() {
    }

    public SubscriptionPlan(String code, String name, String userType, BigDecimal priceUsd, int reportsPerMonth) {
        this.code = code;
        this.name = name;
        this.userType = userType;
        this.priceUsd = priceUsd;
        this.reportsPerMonth = reportsPerMonth;
    }

    /** ¿Es para jugadores (PLAYER_*)? */
    public boolean forPlayer() {
        return "PLAYER".equals(userType);
    }

    /** ¿Es un plan del perfil? (PLAYER_* para jugadores, CLUB_* para clubes, DT_* para DT.) */
    public boolean forProfile(Profile profile) {
        return profile.userType().equals(userType);
    }

    /** "jugadores", "clubes" o "DT", para los mensajes. */
    public String audience() {
        return switch (userType) {
            case "PLAYER" -> "jugadores";
            case "COACH" -> "DT";
            default -> "clubes";
        };
    }

    /** Último día de una suscripción de este tipo que empieza en {@code from} (el del plan, si tiene fecha fija). */
    public LocalDate to(LocalDate from) {
        return validUntil != null ? validUntil : from.plusMonths(1).minusDays(1);
    }

    /** ¿Se puede usar (y contratar) el {@code day}? Los planes con fecha fija terminan en su {@link #validUntil}. */
    public boolean isValid(LocalDate day) {
        return validUntil == null || !day.isAfter(validUntil);
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getUserType() { return userType; }
    public BigDecimal getPriceUsd() { return priceUsd; }
    public int getReportsPerMonth() { return reportsPerMonth; }
    public int getMaxTeams() { return maxTeams; }
    public int getMatchesPerMonth() { return matchesPerMonth; }
    public LocalDate getValidUntil() { return validUntil; }
}
