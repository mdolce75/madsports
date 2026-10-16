package com.rinkmetrics.dt;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un jugador del plantel de un equipo del DT (tabla {@code jugador_dt}). {@code jugadorId} es el id con el que lo
 * nombran las acciones: el id_player de SIDGAD, o uno propio si lo agregó el DT. Dar de baja lo deja inactivo.
 */
@Entity
@Table(name = "jugador_dt")
public class JugadorDt {

    /** Primer id de los jugadores que agrega el DT (los de SIDGAD son menores). */
    static final int PRIMER_ID_PROPIO = 900_000_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long usuarioId;
    private String categoria;
    private String rama;
    private int jugadorId;
    private String nombre;
    private String apellido;
    private String numero;
    private boolean arquero;
    private boolean activo = true;
    private Instant creado;

    protected JugadorDt() {
    }

    JugadorDt(Long usuarioId, String categoria, String rama, int jugadorId, String nombre, String apellido,
              String numero, boolean arquero, Instant creado) {
        this.usuarioId = usuarioId;
        this.categoria = categoria;
        this.rama = rama;
        this.jugadorId = jugadorId;
        this.nombre = nombre;
        this.apellido = apellido;
        this.numero = numero;
        this.arquero = arquero;
        this.creado = creado;
    }

    void cambiar(String nombre, String apellido, String numero, boolean arquero) {
        this.nombre = nombre;
        this.apellido = apellido;
        this.numero = numero;
        this.arquero = arquero;
    }

    void baja() {
        activo = false;
    }

    /** Nombre y apellido ("Juan Pérez"). */
    public String completo() {
        return (nombre + " " + apellido).trim();
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public String getCategoria() { return categoria; }
    public String getRama() { return rama; }
    public int getJugadorId() { return jugadorId; }
    public String getNombre() { return nombre; }
    public String getApellido() { return apellido; }
    public String getNumero() { return numero; }
    public boolean isArquero() { return arquero; }
    public boolean isActivo() { return activo; }
    public Instant getCreado() { return creado; }
}
