package com.madsports.coach;

import jakarta.persistence.Column;
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
@Table(name = "coach_action")
public class MatchAction {

    @Id
    private String id;
    private Long matchId;
    /** id_player de SIDGAD; 0 si es un jugador agregado a mano. */
    private int playerId;
    private String playerName;
    @Enumerated(EnumType.STRING)
    private ActionType kind;
    @Enumerated(EnumType.STRING)
    private Outcome outcome;
    /** 1 o 2. */
    @Column(name = "match_period")
    private int period;
    /** Segundos jugados del tiempo (no lo que marcaba el reloj): 18:30 en el reloj de un tiempo de 25' = 390. */
    private int atSecond;
    private Instant createdAt;
    private Instant deletedAt;

    protected MatchAction() {
    }

    public MatchAction(String id, Long matchId, int playerId, String playerName, ActionType kind,
                    Outcome outcome, int period, int atSecond, Instant createdAt) {
        this.id = id;
        this.matchId = matchId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.kind = kind;
        this.outcome = outcome;
        this.period = period;
        this.atSecond = atSecond;
        this.createdAt = createdAt;
    }

    void delete(Instant now) {
        if (deletedAt == null) {
            deletedAt = now;
        }
    }

    public boolean isActive() {
        return deletedAt == null;
    }

    public String getId() { return id; }
    public Long getMatchId() { return matchId; }
    public int getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public ActionType getKind() { return kind; }
    public Outcome getOutcome() { return outcome; }
    public int getPeriod() { return period; }
    public int getAtSecond() { return atSecond; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
