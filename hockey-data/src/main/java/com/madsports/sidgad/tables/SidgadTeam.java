package com.madsports.sidgad.tables;

import com.madsports.sidgad.model.Models.TeamRef;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Un equipo de una competición (tabla {@code sidgad_team}): del {@code teams_array_<idc>} del catálogo. */
@Entity
@Table(name = "sidgad_team")
public class SidgadTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int competitionId;
    /** Id del equipo en SIDGAD (cambia en cada competición). */
    private int teamId;
    private int clubId;
    private String code;
    private String name;
    /** Archivo del escudo en {@code logos_clubes/}. */
    private String logo;

    protected SidgadTeam() {
    }

    SidgadTeam(int competitionId, TeamRef t) {
        this.competitionId = competitionId;
        this.teamId = t.teamId();
        this.clubId = t.clubId();
        this.code = t.code();
        this.name = t.name();
        this.logo = t.logo();
    }

    public Long getId() { return id; }
    public int getCompetitionId() { return competitionId; }
    public int getTeamId() { return teamId; }
    public int getClubId() { return clubId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getLogo() { return logo; }
}
