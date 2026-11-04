package com.madsports.sidgad.tables;

import com.madsports.sidgad.model.Models.LineupPlayer;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un jugador en la planilla de un partido (tabla {@code sidgad_match_lineup}), con lo que hizo según el acta. */
@Entity
@Table(name = "sidgad_match_lineup")
public class SidgadMatchLineup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int idp;
    /** id_player; null si el acta no lo trae. */
    private Integer playerId;
    /** Planilla del local. */
    private boolean home;
    /** El equipo como figura en el acta ("VELEZ", "GEBA B"). */
    private String teamName;
    private String shirtNumber;
    private String name;
    private boolean starter;
    private boolean goalkeeper;
    private int goals;
    private int yellowCards;
    private int blueCards;
    private int redCards;
    private int penaltiesScored;
    private int penaltiesTaken;
    private int directFreeScored;
    private int directFreeTaken;

    protected SidgadMatchLineup() {
    }

    SidgadMatchLineup(int idp, LineupPlayer p) {
        this.idp = idp;
        this.playerId = p.playerId() > 0 ? p.playerId() : null;
        this.home = p.home();
        this.teamName = p.team();
        this.shirtNumber = p.number();
        this.name = p.name();
        this.starter = p.starter();
        this.goalkeeper = p.goalkeeper();
        this.goals = p.goals();
        this.yellowCards = p.yellowCards();
        this.blueCards = p.blueCards();
        this.redCards = p.redCards();
        this.penaltiesScored = p.penaltiesScored();
        this.penaltiesTaken = p.penaltiesTaken();
        this.directFreeScored = p.directFreeScored();
        this.directFreeTaken = p.directFreeTaken();
    }

    public Long getId() { return id; }
    public int getIdp() { return idp; }
    public Integer getPlayerId() { return playerId; }
    public boolean isHome() { return home; }
    public String getTeamName() { return teamName; }
    public String getShirtNumber() { return shirtNumber; }
    public String getName() { return name; }
    public boolean isStarter() { return starter; }
    public boolean isGoalkeeper() { return goalkeeper; }
    public int getGoals() { return goals; }
    public int getYellowCards() { return yellowCards; }
    public int getBlueCards() { return blueCards; }
    public int getRedCards() { return redCards; }
    public int getPenaltiesScored() { return penaltiesScored; }
    public int getPenaltiesTaken() { return penaltiesTaken; }
    public int getDirectFreeScored() { return directFreeScored; }
    public int getDirectFreeTaken() { return directFreeTaken; }
}
