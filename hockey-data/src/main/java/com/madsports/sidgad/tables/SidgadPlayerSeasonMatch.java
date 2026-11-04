package com.madsports.sidgad.tables;

import com.madsports.sidgad.model.Models.SeasonMatch;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Un partido de la ficha de temporada de un jugador (tabla {@code sidgad_player_season_match}), con lo que se le anotó.
 * SIDGAD lo lista aunque el partido no tenga acta publicada (idp null).
 */
@Entity
@Table(name = "sidgad_player_season_match")
public class SidgadPlayerSeasonMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int playerId;
    private int seasonId;
    /** El nombre de la competición, como figura en la ficha. */
    private String competitionName;
    private LocalDate matchDate;
    private String homeName;
    private String awayName;
    private int homeGoals;
    private int awayGoals;
    private Integer idp;
    private int goals;
    private int assists;
    private int directFreeScored;
    private int directFreeTaken;
    private int penaltiesScored;
    private int penaltiesTaken;
    private int blueCards;
    private int redCards;

    protected SidgadPlayerSeasonMatch() {
    }

    SidgadPlayerSeasonMatch(int playerId, int seasonId, SeasonMatch m) {
        this.playerId = playerId;
        this.seasonId = seasonId;
        this.competitionName = m.competition();
        this.matchDate = m.date();
        this.homeName = m.home();
        this.awayName = m.away();
        this.homeGoals = m.homeGoals();
        this.awayGoals = m.awayGoals();
        this.idp = m.idp() > 0 ? m.idp() : null;
        this.goals = m.goals();
        this.assists = m.assists();
        this.directFreeScored = m.directFreeScored();
        this.directFreeTaken = m.directFreeTaken();
        this.penaltiesScored = m.penaltiesScored();
        this.penaltiesTaken = m.penaltiesTaken();
        this.blueCards = m.blueCards();
        this.redCards = m.redCards();
    }

    public Long getId() { return id; }
    public int getPlayerId() { return playerId; }
    public int getSeasonId() { return seasonId; }
    public String getCompetitionName() { return competitionName; }
    public LocalDate getMatchDate() { return matchDate; }
    public String getHomeName() { return homeName; }
    public String getAwayName() { return awayName; }
    public int getHomeGoals() { return homeGoals; }
    public int getAwayGoals() { return awayGoals; }
    public Integer getIdp() { return idp; }
    public int getGoals() { return goals; }
    public int getAssists() { return assists; }
    public int getDirectFreeScored() { return directFreeScored; }
    public int getDirectFreeTaken() { return directFreeTaken; }
    public int getPenaltiesScored() { return penaltiesScored; }
    public int getPenaltiesTaken() { return penaltiesTaken; }
    public int getBlueCards() { return blueCards; }
    public int getRedCards() { return redCards; }
}
