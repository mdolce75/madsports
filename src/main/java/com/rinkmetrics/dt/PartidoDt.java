package com.rinkmetrics.dt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un partido que carga un DT (tabla {@code partido_dt}), con su reloj.
 * <p>
 * El reloj cuenta hacia atrás los minutos de cada tiempo (25:00 a 00:00) y no se cuenta de a un segundo: se calcula
 * con la hora en que arrancó o se reanudó ({@code relojDesde}, null en pausa) y los segundos jugados hasta ese momento
 * ({@code relojSegundos}). Así, si el teléfono se bloquea o se recarga la página, el minuto sigue siendo el correcto.
 * Los métodos del reloj reciben la hora para poder probarlos.
 */
@Entity
@Table(name = "partido_dt")
public class PartidoDt {

    /** Un partido cerrado se puede reabrir durante este tiempo. */
    static final Duration REAPERTURA = Duration.ofHours(48);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long usuarioId;
    private String federacion;
    private int clubId;
    /** Categoría con la letra del equipo ("JUVENIL:A") y rama, como en los equipos del DT. */
    private String categoria;
    private String rama;
    /** Competición y partido de SIDGAD (null en un amistoso). */
    private Integer idc;
    private Integer idp;
    private String torneo;
    private String rival;
    private LocalDate fecha;
    @Column(name = "es_local")
    private boolean local;
    private int minutosTiempo;
    @Enumerated(EnumType.STRING)
    private EstadoPartido estado;
    private int tiempoActual = 1;
    private Instant relojDesde;
    private int relojSegundos;
    private int golesFavor;
    private int golesContra;
    private Instant creado;
    private Instant cerrado;

    protected PartidoDt() {
    }

    public PartidoDt(Long usuarioId, String federacion, int clubId, String categoria, String rama, Integer idc,
                     Integer idp, String torneo, String rival, LocalDate fecha, boolean local, int minutosTiempo,
                     Instant creado) {
        this.usuarioId = usuarioId;
        this.federacion = federacion;
        this.clubId = clubId;
        this.categoria = categoria;
        this.rama = rama;
        this.idc = idc;
        this.idp = idp;
        this.torneo = torneo;
        this.rival = rival;
        this.fecha = fecha;
        this.local = local;
        this.minutosTiempo = minutosTiempo;
        this.estado = EstadoPartido.PENDIENTE;
        this.creado = creado;
    }

    /** Duración de cada tiempo, en segundos. */
    public int duracion() {
        return minutosTiempo * 60;
    }

    /** Segundos jugados del tiempo actual (entre 0 y la duración). */
    public int jugados(Instant ahora) {
        long s = relojSegundos + (relojDesde == null ? 0 : Duration.between(relojDesde, ahora).getSeconds());
        return (int) Math.max(0, Math.min(duracion(), s));
    }

    /** Lo que marca el reloj: segundos que faltan del tiempo actual. */
    public int restante(Instant ahora) {
        return duracion() - jugados(ahora);
    }

    public boolean corriendo() {
        return relojDesde != null;
    }

    public boolean aceptaAcciones() {
        return estado != EstadoPartido.CERRADO;
    }

    /** Arranca el 1T (partido pendiente) o el 2T (entretiempo): el reloj vuelve a la duración y empieza a descontar. */
    void iniciar(Instant ahora) {
        if (estado == EstadoPartido.PENDIENTE) {
            tiempoActual = 1;
        } else if (estado == EstadoPartido.ENTRETIEMPO) {
            tiempoActual = 2;
        } else {
            throw new IllegalStateException("El partido ya está en juego o terminó.");
        }
        estado = EstadoPartido.EN_JUEGO;
        relojSegundos = 0;
        relojDesde = ahora;
    }

    void pausar(Instant ahora) {
        enJuego();
        if (!corriendo()) {
            throw new IllegalStateException("El reloj ya está en pausa.");
        }
        relojSegundos = jugados(ahora);
        relojDesde = null;
    }

    void reanudar(Instant ahora) {
        enJuego();
        if (corriendo()) {
            throw new IllegalStateException("El reloj ya está corriendo.");
        }
        if (jugados(ahora) >= duracion()) {
            throw new IllegalStateException("El tiempo ya terminó.");
        }
        relojDesde = ahora;
    }

    /** Termina el tiempo actual: el reloj queda en 00:00; después del 1T viene el entretiempo, después del 2T el final. */
    void finTiempo(Instant ahora) {
        enJuego();
        relojSegundos = duracion();
        relojDesde = null;
        estado = tiempoActual == 1 ? EstadoPartido.ENTRETIEMPO : EstadoPartido.TERMINADO;
    }

    /**
     * Corrige el reloj (si se inició tarde, por ejemplo): {@code segundos} positivos adelantan el tiempo jugado (el
     * reloj marca menos), negativos lo atrasan. Sigue corriendo o en pausa como estaba.
     */
    void ajustar(int segundos, Instant ahora) {
        enJuego();
        int nuevos = Math.max(0, Math.min(duracion(), jugados(ahora) + segundos));
        relojSegundos = nuevos;
        if (corriendo()) {
            relojDesde = ahora;
        }
    }

    /** Cierra el partido: deja el reloj parado y ya no acepta acciones. */
    void cerrar(Instant ahora) {
        if (estado == EstadoPartido.CERRADO) {
            throw new IllegalStateException("El partido ya está cerrado.");
        }
        if (corriendo()) {
            relojSegundos = jugados(ahora);
            relojDesde = null;
        }
        estado = EstadoPartido.CERRADO;
        cerrado = ahora;
    }

    /** Reabre un partido cerrado hace menos de 48 h (queda terminado: acepta acciones, el reloj sigue parado). */
    void reabrir(Instant ahora) {
        if (estado != EstadoPartido.CERRADO) {
            throw new IllegalStateException("El partido no está cerrado.");
        }
        if (Duration.between(cerrado, ahora).compareTo(REAPERTURA) > 0) {
            throw new IllegalStateException("Pasaron más de 48 horas desde que se cerró: ya no se puede reabrir.");
        }
        estado = EstadoPartido.TERMINADO;
        cerrado = null;
    }

    void marcador(int favor, int contra) {
        this.golesFavor = favor;
        this.golesContra = contra;
    }

    private void enJuego() {
        if (estado != EstadoPartido.EN_JUEGO) {
            throw new IllegalStateException(switch (estado) {
                case PENDIENTE -> "Primero iniciá el partido.";
                case ENTRETIEMPO -> "Es el entretiempo: iniciá el 2T.";
                default -> "El partido ya terminó.";
            });
        }
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public String getFederacion() { return federacion; }
    public int getClubId() { return clubId; }
    public String getCategoria() { return categoria; }
    public String getRama() { return rama; }
    public Integer getIdc() { return idc; }
    public Integer getIdp() { return idp; }
    public String getTorneo() { return torneo; }
    public String getRival() { return rival; }
    public LocalDate getFecha() { return fecha; }
    public boolean isLocal() { return local; }
    public int getMinutosTiempo() { return minutosTiempo; }
    public EstadoPartido getEstado() { return estado; }
    public int getTiempoActual() { return tiempoActual; }
    public int getGolesFavor() { return golesFavor; }
    public int getGolesContra() { return golesContra; }
    public Instant getCreado() { return creado; }
    public Instant getCerrado() { return cerrado; }
}
