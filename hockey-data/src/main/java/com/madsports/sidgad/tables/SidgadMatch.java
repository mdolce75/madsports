package com.madsports.sidgad.tables;

import com.madsports.sidgad.model.Models.Match;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalTime;

/** Un partido del calendario de una competición (tabla {@code sidgad_match}). */
@Entity
@Table(name = "sidgad_match")
public class SidgadMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int competitionId;
    /** Id del acta en SIDGAD; null si el partido todavía no tiene. */
    private Integer idp;
    private int round;
    private LocalDate matchDate;
    private LocalTime matchTime;
    private String venue;
    private int homeTeamId;
    private int homeClubId;
    private String homeName;
    private int awayTeamId;
    private int awayClubId;
    private String awayName;
    /** Goles; null si no se jugó. */
    private Integer homeGoals;
    private Integer awayGoals;
    private boolean played;

    protected SidgadMatch() {
    }

    SidgadMatch(int competitionId, Match m) {
        this.competitionId = competitionId;
        this.idp = m.idp() > 0 ? m.idp() : null;
        this.round = m.round();
        this.matchDate = m.date();
        this.matchTime = m.time();
        this.venue = m.venue();
        this.homeTeamId = m.homeTeamId();
        this.homeClubId = m.homeClubId();
        this.homeName = m.homeName();
        this.awayTeamId = m.awayTeamId();
        this.awayClubId = m.awayClubId();
        this.awayName = m.awayName();
        this.homeGoals = m.homeGoals();
        this.awayGoals = m.awayGoals();
        this.played = m.played();
    }

    public Long getId() { return id; }
    public int getCompetitionId() { return competitionId; }
    public Integer getIdp() { return idp; }
    public int getRound() { return round; }
    public LocalDate getMatchDate() { return matchDate; }
    public LocalTime getMatchTime() { return matchTime; }
    public String getVenue() { return venue; }
    public int getHomeTeamId() { return homeTeamId; }
    public int getHomeClubId() { return homeClubId; }
    public String getHomeName() { return homeName; }
    public int getAwayTeamId() { return awayTeamId; }
    public int getAwayClubId() { return awayClubId; }
    public String getAwayName() { return awayName; }
    public Integer getHomeGoals() { return homeGoals; }
    public Integer getAwayGoals() { return awayGoals; }
    public boolean isPlayed() { return played; }
}
