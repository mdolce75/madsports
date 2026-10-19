package com.rinkmetrics.dt;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Un equipo del DT (tabla {@code equipo_dt}): lo crea él en el ABM de equipos, con un nombre libre. */
@Entity
@Table(name = "equipo_dt")
public class Equipo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long usuarioId;
    private String nombre;
    private Instant creado;

    protected Equipo() {
    }

    Equipo(Long usuarioId, String nombre, Instant creado) {
        this.usuarioId = usuarioId;
        this.nombre = nombre;
        this.creado = creado;
    }

    void renombrar(String nombre) {
        this.nombre = nombre;
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public String getNombre() { return nombre; }
    public Instant getCreado() { return creado; }
}
