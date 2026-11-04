package com.madsports.sidgad.tables;

import com.madsports.sidgad.model.Models.StandingRow;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Una fila de la tabla de posiciones de una competición (tabla {@code sidgad_standing}). */
@Entity
@Table(name = "sidgad_standing")
public class SidgadStanding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int competitionId;
    /** El grupo ("IMPAR", "PAR"; vacío si la competición tiene uno solo). */
    private String groupName;
    private int position;
    private int clubId;
    private String name;
    private String code;
    private int points;
    private int played;
    private int won;
    private int drawn;
    private int lost;
    private int goalsFor;
    private int goalsAgainst;
    private int goalDiff;

    protected SidgadStanding() {
    }

    SidgadStanding(int competitionId, StandingRow r) {
        this.competitionId = competitionId;
        this.groupName = r.group();
        this.position = r.position();
        this.clubId = r.clubId();
        this.name = r.name();
        this.code = r.code();
        this.points = r.points();
        this.played = r.played();
        this.won = r.won();
        this.drawn = r.drawn();
        this.lost = r.lost();
        this.goalsFor = r.goalsFor();
        this.goalsAgainst = r.goalsAgainst();
        this.goalDiff = r.goalDiff();
    }

    public Long getId() { return id; }
    public int getCompetitionId() { return competitionId; }
    public String getGroupName() { return groupName; }
    public int getPosition() { return position; }
    public int getClubId() { return clubId; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public int getPoints() { return points; }
    public int getPlayed() { return played; }
    public int getWon() { return won; }
    public int getDrawn() { return drawn; }
    public int getLost() { return lost; }
    public int getGoalsFor() { return goalsFor; }
    public int getGoalsAgainst() { return goalsAgainst; }
    public int getGoalDiff() { return goalDiff; }
}
