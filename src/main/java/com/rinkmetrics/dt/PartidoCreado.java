package com.rinkmetrics.dt;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un partido que el DT empezó a cargar (tabla {@code partido_creado_dt}): cuenta para el cupo del mes aunque después
 * elimine el partido.
 */
@Entity
@Table(name = "partido_creado_dt")
public class PartidoCreado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long usuarioId;
    private Instant creado;

    protected PartidoCreado() {
    }

    PartidoCreado(Long usuarioId, Instant creado) {
        this.usuarioId = usuarioId;
        this.creado = creado;
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public Instant getCreado() { return creado; }
}
