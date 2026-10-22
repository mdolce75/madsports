package com.madsports.report;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Un informe que pidió el usuario (tabla {@code informe_pedido}): cuenta para el cupo del mes. */
@Entity
@Table(name = "requested_report")
public class RequestedReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private Instant createdAt;

    protected RequestedReport() {
    }

    RequestedReport(Long userId, Instant createdAt) {
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
}
