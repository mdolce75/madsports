package com.madsports.report;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un informe que pidió el usuario (tabla {@code requested_report}): cuenta para el cupo del mes y queda en su historial
 * de informes generados, con la fecha y la hora y qué informe fue ({@code detail}: "Club completo · Apertura + Clausura
 * 2026").
 */
@Entity
@Table(name = "requested_report")
public class RequestedReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private Instant createdAt;
    /** Qué informe fue; vacío en los pedidos de antes del historial. */
    private String detail;

    protected RequestedReport() {
    }

    RequestedReport(Long userId, Instant createdAt, String detail) {
        this.userId = userId;
        this.createdAt = createdAt;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDetail() { return detail; }
}
