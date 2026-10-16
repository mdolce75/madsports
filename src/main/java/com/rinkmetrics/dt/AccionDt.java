package com.rinkmetrics.dt;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Una acción de un jugador en un partido de la planilla (tabla {@code accion_dt}). El id lo genera el teléfono
 * (uuid), así una acción cargada sin señal se puede reenviar sin duplicarse. Deshacer la marca como borrada.
 */
@Entity
@Table(name = "accion_dt")
public class AccionDt {

    @Id
    private String id;
    private Long partidoId;
    /** id_player de SIDGAD; 0 si es un jugador agregado a mano. */
    private int jugadorId;
    private String jugadorNombre;
    @Enumerated(EnumType.STRING)
    private TipoAccion tipo;
    @Enumerated(EnumType.STRING)
    private Resultado resultado;
    /** 1 o 2. */
    private int tiempo;
    /** Segundos jugados del tiempo (no lo que marcaba el reloj): 18:30 en el reloj de un tiempo de 25' = 390. */
    private int segundo;
    private Instant creada;
    private Instant borrada;

    protected AccionDt() {
    }

    public AccionDt(String id, Long partidoId, int jugadorId, String jugadorNombre, TipoAccion tipo,
                    Resultado resultado, int tiempo, int segundo, Instant creada) {
        this.id = id;
        this.partidoId = partidoId;
        this.jugadorId = jugadorId;
        this.jugadorNombre = jugadorNombre;
        this.tipo = tipo;
        this.resultado = resultado;
        this.tiempo = tiempo;
        this.segundo = segundo;
        this.creada = creada;
    }

    void borrar(Instant ahora) {
        if (borrada == null) {
            borrada = ahora;
        }
    }

    public boolean activa() {
        return borrada == null;
    }

    public String getId() { return id; }
    public Long getPartidoId() { return partidoId; }
    public int getJugadorId() { return jugadorId; }
    public String getJugadorNombre() { return jugadorNombre; }
    public TipoAccion getTipo() { return tipo; }
    public Resultado getResultado() { return resultado; }
    public int getTiempo() { return tiempo; }
    public int getSegundo() { return segundo; }
    public Instant getCreada() { return creada; }
    public Instant getBorrada() { return borrada; }
}
