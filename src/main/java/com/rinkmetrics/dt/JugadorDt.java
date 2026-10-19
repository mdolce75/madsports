package com.rinkmetrics.dt;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Un jugador del DT (tabla {@code jugador_dt}), en uno o más de sus equipos ({@code jugador_equipo_dt}).
 * {@code jugadorId} es el id con el que lo nombran las acciones. Dar de baja lo deja inactivo: sus acciones quedan.
 */
@Entity
@Table(name = "jugador_dt")
public class JugadorDt {

    /** Primer id de los jugadores del DT (las acciones de antes usan ids de SIDGAD, que son menores). */
    static final int PRIMER_ID_PROPIO = 900_000_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long usuarioId;
    private int jugadorId;
    private String nombre;
    private String apellido;
    private String numero;
    private boolean arquero;
    private boolean activo = true;
    private Instant creado;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "jugador_equipo_dt", joinColumns = @JoinColumn(name = "jugador_dt_id"))
    @Column(name = "equipo_id")
    private Set<Long> equipos = new HashSet<>();

    protected JugadorDt() {
    }

    JugadorDt(Long usuarioId, int jugadorId, String nombre, String apellido, String numero, boolean arquero,
              Collection<Long> equipos, Instant creado) {
        this.usuarioId = usuarioId;
        this.jugadorId = jugadorId;
        this.nombre = nombre;
        this.apellido = apellido;
        this.numero = numero;
        this.arquero = arquero;
        this.equipos = new HashSet<>(equipos);
        this.creado = creado;
    }

    void cambiar(String nombre, String apellido, String numero, boolean arquero, Collection<Long> equipos) {
        this.nombre = nombre;
        this.apellido = apellido;
        this.numero = numero;
        this.arquero = arquero;
        this.equipos = new HashSet<>(equipos);
    }

    void baja() {
        activo = false;
    }

    void sacarDe(Long equipoId) {
        equipos.remove(equipoId);
    }

    public boolean juegaEn(Long equipoId) {
        return equipos.contains(equipoId);
    }

    /** Nombre y apellido ("Juan Pérez"). */
    public String completo() {
        return (nombre + " " + apellido).trim();
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public int getJugadorId() { return jugadorId; }
    public String getNombre() { return nombre; }
    public String getApellido() { return apellido; }
    public String getNumero() { return numero; }
    public boolean isArquero() { return arquero; }
    public boolean isActivo() { return activo; }
    public Instant getCreado() { return creado; }
    public Set<Long> getEquipos() { return Set.copyOf(equipos); }
}
