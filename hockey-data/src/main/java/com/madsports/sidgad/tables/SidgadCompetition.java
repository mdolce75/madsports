package com.madsports.sidgad.tables;

import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Una competición del catálogo de SIDGAD (tabla {@code sidgad_competition}); el id es el idc de SIDGAD. */
@Entity
@Table(name = "sidgad_competition")
public class SidgadCompetition {

    @Id
    private Integer id;
    private int federationId;
    /** FPP o AMP; null si SIDGAD la lista pero no es de una federación relevada. */
    private String federation;
    private int seasonId;
    /** Año de la temporada (del portal); null si no se conoce. */
    private Integer seasonYear;
    private String name;
    /** OPENING o CLOSING; null si el nombre no sigue el patrón de la federación. */
    private String tournament;
    /** "PRIMERA", "INFANTIL"... (null como {@code tournament}). */
    private String level;
    /** MASCULINO o FEMENINO (null como {@code tournament}). */
    private String branch;
    private int division;
    private int periodMinutes;
    private Instant updatedAt;

    protected SidgadCompetition() {
    }

    SidgadCompetition(int id) {
        this.id = id;
    }

    /** Copia los datos de la competición parseada. */
    void update(Competition c, Integer year, Instant now) {
        Federation f = c.federation();
        this.federationId = c.federationId();
        this.federation = f == null ? null : f.name();
        this.seasonId = c.seasonId();
        this.seasonYear = year;
        this.name = c.name();
        this.tournament = c.tournament() == null ? null : c.tournament().name();
        this.level = c.category() == null ? null : c.category().level();
        this.branch = c.category() == null ? null : c.category().branch();
        this.division = c.division();
        this.periodMinutes = c.periodMinutes();
        this.updatedAt = now;
    }

    public Integer getId() { return id; }
    public int getFederationId() { return federationId; }
    public String getFederation() { return federation; }
    public int getSeasonId() { return seasonId; }
    public Integer getSeasonYear() { return seasonYear; }
    public String getName() { return name; }
    public String getTournament() { return tournament; }
    public String getLevel() { return level; }
    public String getBranch() { return branch; }
    public int getDivision() { return division; }
    public int getPeriodMinutes() { return periodMinutes; }
    public Instant getUpdatedAt() { return updatedAt; }
}
