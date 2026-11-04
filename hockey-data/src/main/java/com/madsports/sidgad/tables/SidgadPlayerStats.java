package com.madsports.sidgad.tables;

import com.madsports.sidgad.model.Models.PlayerStats;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Las estadísticas de un jugador en una competición (tabla {@code sidgad_player_stats}), como las publica SIDGAD. */
@Entity
@Table(name = "sidgad_player_stats")
public class SidgadPlayerStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int competitionId;
    private int playerId;
    private int teamId;
    private String teamCode;
    private int clubId;
    private String name;
    private int goals;
    private int games;
    private int assists;
    private int penaltiesScored;
    private int penaltiesTaken;
    private int directFreeScored;
    private int directFreeTaken;
    private int blueCards;
    private int redCards;

    protected SidgadPlayerStats() {
    }

    SidgadPlayerStats(int competitionId, PlayerStats s) {
        this.competitionId = competitionId;
        this.playerId = s.playerId();
        this.teamId = s.teamId();
        this.teamCode = s.teamCode();
        this.clubId = s.clubId();
        this.name = s.name();
        this.goals = s.goals();
        this.games = s.games();
        this.assists = s.assists();
        this.penaltiesScored = s.penaltiesScored();
        this.penaltiesTaken = s.penaltiesTaken();
        this.directFreeScored = s.directFreeScored();
        this.directFreeTaken = s.directFreeTaken();
        this.blueCards = s.blueCards();
        this.redCards = s.redCards();
    }

    public Long getId() { return id; }
    public int getCompetitionId() { return competitionId; }
    public int getPlayerId() { return playerId; }
    public int getTeamId() { return teamId; }
    public String getTeamCode() { return teamCode; }
    public int getClubId() { return clubId; }
    public String getName() { return name; }
    public int getGoals() { return goals; }
    public int getGames() { return games; }
    public int getAssists() { return assists; }
    public int getPenaltiesScored() { return penaltiesScored; }
    public int getPenaltiesTaken() { return penaltiesTaken; }
    public int getDirectFreeScored() { return directFreeScored; }
    public int getDirectFreeTaken() { return directFreeTaken; }
    public int getBlueCards() { return blueCards; }
    public int getRedCards() { return redCards; }
}
