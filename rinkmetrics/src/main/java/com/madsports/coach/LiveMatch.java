package com.madsports.coach;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un partido que carga un DT (tabla {@code partido_dt}), con su reloj.
 * <p>
 * El reloj cuenta hacia atrás los minutos de cada tiempo (25:00 a 00:00) y no se cuenta de a un segundo: se calcula
 * con la hora en que arrancó o se reanudó ({@code clockStartedAt}, null en pausa) y los segundos jugados hasta ese momento
 * ({@code clockSeconds}). Así, si el teléfono se bloquea o se recarga la página, el minuto sigue siendo el correcto.
 * Los métodos del reloj reciben la hora para poder probarlos.
 */
@Entity
@Table(name = "coach_match")
public class LiveMatch {

    /** Un partido cerrado se puede reabrir durante este tiempo. */
    static final Duration REOPEN_WINDOW = Duration.ofHours(48);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private String federation;
    private int clubId;
    /** El equipo del DT (sección Equipo). */
    @Column(name = "team_id")
    private Long teamId;
    /** El mismo equipo, para leer su nombre (se escribe por {@code teamId}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", insertable = false, updatable = false)
    private Team team;
    /** Competición y partido de SIDGAD (null en un amistoso). */
    private Integer idc;
    private Integer idp;
    private String tournament;
    private String opponent;
    @Column(name = "match_date")
    private LocalDate date;
    private boolean home;
    private int periodMinutes;
    @Enumerated(EnumType.STRING)
    private MatchStatus status;
    private int currentPeriod = 1;
    private Instant clockStartedAt;
    private int clockSeconds;
    private int goalsFor;
    private int goalsAgainst;
    private Instant createdAt;
    private Instant closedAt;

    protected LiveMatch() {
    }

    public LiveMatch(Long userId, String federation, int clubId, Long teamId, Integer idc, Integer idp,
                     String tournament, String opponent, LocalDate date, boolean home, int periodMinutes, Instant createdAt) {
        this.userId = userId;
        this.federation = federation;
        this.clubId = clubId;
        this.teamId = teamId;
        this.idc = idc;
        this.idp = idp;
        this.tournament = tournament;
        this.opponent = opponent;
        this.date = date;
        this.home = home;
        this.periodMinutes = periodMinutes;
        this.status = MatchStatus.PENDING;
        this.createdAt = createdAt;
    }

    /** Duración de cada tiempo, en segundos. */
    public int duration() {
        return periodMinutes * 60;
    }

    /** Segundos jugados del tiempo actual (entre 0 y la duración). */
    public int elapsed(Instant now) {
        long s = clockSeconds + (clockStartedAt == null ? 0 : Duration.between(clockStartedAt, now).getSeconds());
        return (int) Math.max(0, Math.min(duration(), s));
    }

    /** Lo que marca el reloj: segundos que faltan del tiempo actual. */
    public int remaining(Instant now) {
        return duration() - elapsed(now);
    }

    public boolean running() {
        return clockStartedAt != null;
    }

    public boolean acceptsActions() {
        return status != MatchStatus.CLOSED;
    }

    /** Arranca el 1T (partido pendiente) o el 2T (entretiempo): el reloj vuelve a la duración y empieza a descontar. */
    void start(Instant now) {
        if (status == MatchStatus.PENDING) {
            currentPeriod = 1;
        } else if (status == MatchStatus.HALF_TIME) {
            currentPeriod = 2;
        } else {
            throw new IllegalStateException("El partido ya está en juego o terminó.");
        }
        status = MatchStatus.IN_PLAY;
        clockSeconds = 0;
        clockStartedAt = now;
    }

    void pause(Instant now) {
        inPlay();
        if (!running()) {
            throw new IllegalStateException("El reloj ya está en pausa.");
        }
        clockSeconds = elapsed(now);
        clockStartedAt = null;
    }

    void resume(Instant now) {
        inPlay();
        if (running()) {
            throw new IllegalStateException("El reloj ya está corriendo.");
        }
        if (elapsed(now) >= duration()) {
            throw new IllegalStateException("El tiempo ya terminó.");
        }
        clockStartedAt = now;
    }

    /** Termina el tiempo actual: el reloj queda en 00:00; después del 1T viene el entretiempo, después del 2T el final. */
    void endPeriod(Instant now) {
        inPlay();
        clockSeconds = duration();
        clockStartedAt = null;
        status = currentPeriod == 1 ? MatchStatus.HALF_TIME : MatchStatus.FINISHED;
    }

    /**
     * Corrige el reloj (si se inició tarde, por ejemplo): {@code seconds} positivos adelantan el tiempo jugado (el
     * reloj marca menos), negativos lo atrasan. Sigue corriendo o en pausa como estaba.
     */
    void adjust(int seconds, Instant now) {
        inPlay();
        int newOnes = Math.max(0, Math.min(duration(), elapsed(now) + seconds));
        clockSeconds = newOnes;
        if (running()) {
            clockStartedAt = now;
        }
    }

    /** Cierra el partido: deja el reloj parado y ya no acepta acciones. */
    void close(Instant now) {
        if (status == MatchStatus.CLOSED) {
            throw new IllegalStateException("El partido ya está cerrado.");
        }
        if (running()) {
            clockSeconds = elapsed(now);
            clockStartedAt = null;
        }
        status = MatchStatus.CLOSED;
        closedAt = now;
    }

    /** Reabre un partido cerrado hace menos de 48 h (queda terminado: acepta acciones, el reloj sigue parado). */
    void reopen(Instant now) {
        if (status != MatchStatus.CLOSED) {
            throw new IllegalStateException("El partido no está cerrado.");
        }
        if (Duration.between(closedAt, now).compareTo(REOPEN_WINDOW) > 0) {
            throw new IllegalStateException("Pasaron más de 48 horas desde que se cerró: ya no se puede reabrir.");
        }
        status = MatchStatus.FINISHED;
        closedAt = null;
    }

    void score(int goalsFor, int against) {
        this.goalsFor = goalsFor;
        this.goalsAgainst = against;
    }

    private void inPlay() {
        if (status != MatchStatus.IN_PLAY) {
            throw new IllegalStateException(switch (status) {
                case PENDING -> "Primero iniciá el partido.";
                case HALF_TIME -> "Es el entretiempo: iniciá el 2T.";
                default -> "El partido ya terminó.";
            });
        }
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getFederation() { return federation; }
    public int getClubId() { return clubId; }
    public Long getTeamId() { return teamId; }
    /** El equipo, si se leyó con el partido (null en uno recién creado). */
    public Team getTeam() { return team; }
    public Integer getIdc() { return idc; }
    public Integer getIdp() { return idp; }
    public String getTournament() { return tournament; }
    public String getOpponent() { return opponent; }
    public LocalDate getDate() { return date; }
    public boolean isHome() { return home; }
    public int getPeriodMinutes() { return periodMinutes; }
    public MatchStatus getStatus() { return status; }
    public int getCurrentPeriod() { return currentPeriod; }
    public int getGoalsFor() { return goalsFor; }
    public int getGoalsAgainst() { return goalsAgainst; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
}
