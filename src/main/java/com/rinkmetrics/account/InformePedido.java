package com.rinkmetrics.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Un informe que pidió el usuario (tabla {@code informe_pedido}): cuenta para el cupo del mes. */
@Entity
@Table(name = "informe_pedido")
public class InformePedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long usuarioId;
    private Instant creado;

    protected InformePedido() {
    }

    InformePedido(Long usuarioId, Instant creado) {
        this.usuarioId = usuarioId;
        this.creado = creado;
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public Instant getCreado() { return creado; }
}
