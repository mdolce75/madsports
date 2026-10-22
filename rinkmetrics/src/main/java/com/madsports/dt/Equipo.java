package com.madsports.dt;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un equipo del DT (tabla {@code equipo_dt}): lo crea él en el ABM de equipos, con un nombre libre o a partir de una
 * categoría de su club en SIDGAD ({@code categoriaSidgad}, p. ej. "PRIMERA" o "INFANTIL:B", y {@code ramaSidgad}), de la
 * que se toma el plantel inicial.
 */
@Entity
@Table(name = "equipo_dt")
public class Equipo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long usuarioId;
    private String nombre;
    private Instant creado;
    private String categoriaSidgad;
    private String ramaSidgad;

    protected Equipo() {
    }

    Equipo(Long usuarioId, String nombre, Instant creado) {
        this(usuarioId, nombre, null, null, creado);
    }

    Equipo(Long usuarioId, String nombre, String categoriaSidgad, String ramaSidgad, Instant creado) {
        this.usuarioId = usuarioId;
        this.nombre = nombre;
        this.categoriaSidgad = categoriaSidgad;
        this.ramaSidgad = ramaSidgad;
        this.creado = creado;
    }

    void renombrar(String nombre) {
        this.nombre = nombre;
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public String getNombre() { return nombre; }
    public Instant getCreado() { return creado; }
    public String getCategoriaSidgad() { return categoriaSidgad; }
    public String getRamaSidgad() { return ramaSidgad; }
}
