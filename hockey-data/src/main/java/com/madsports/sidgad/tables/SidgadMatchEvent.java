package com.madsports.sidgad.tables;

import com.madsports.sidgad.model.Models.MatchEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un evento del acta de un partido (tabla {@code sidgad_match_event}): gol, tarjeta o falta de equipo. */
@Entity
@Table(name = "sidgad_match_event")
public class SidgadMatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int idp;
    /** 1 o 2 (otro número si el acta lo carga fuera del juego, p. ej. 11). */
    private int period;
    /** Minuto y segundo transcurridos dentro del período. */
    private int eventMinute;
    private int eventSecond;
    /** GOAL, YELLOW_CARD, BLUE_CARD, RED_CARD o FOUL. */
    private String eventType;
    /** Equipo al que se atribuye (0 si el acta no lo indica). */
    private int teamId;
    private int clubId;
    private String teamCode;
    private String playerName;
    /** id_player; null si el evento no lo trae. */
    private Integer playerId;

    protected SidgadMatchEvent() {
    }

    SidgadMatchEvent(int idp, MatchEvent e) {
        this.idp = idp;
        this.period = e.period();
        this.eventMinute = e.minute();
        this.eventSecond = e.second();
        this.eventType = e.type().name();
        this.teamId = e.teamId();
        this.clubId = e.clubId();
        this.teamCode = e.teamCode();
        this.playerName = e.player();
        this.playerId = e.playerId() > 0 ? e.playerId() : null;
    }

    public Long getId() { return id; }
    public int getIdp() { return idp; }
    public int getPeriod() { return period; }
    public int getEventMinute() { return eventMinute; }
    public int getEventSecond() { return eventSecond; }
    public String getEventType() { return eventType; }
    public int getTeamId() { return teamId; }
    public int getClubId() { return clubId; }
    public String getTeamCode() { return teamCode; }
    public String getPlayerName() { return playerName; }
    public Integer getPlayerId() { return playerId; }
}
