package com.madsports.coach;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un equipo del DT (tabla {@code equipo_dt}): lo crea él en el ABM de equipos, con un nombre libre o a partir de una
 * categoría de su club en SIDGAD ({@code sidgadCategory}, p. ej. "PRIMERA" o "INFANTIL:B", y {@code sidgadBranch}), de la
 * que se toma el plantel inicial.
 */
@Entity
@Table(name = "coach_team")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private String name;
    private Instant createdAt;
    private String sidgadCategory;
    private String sidgadBranch;

    protected Team() {
    }

    Team(Long userId, String name, Instant createdAt) {
        this(userId, name, null, null, createdAt);
    }

    Team(Long userId, String name, String sidgadCategory, String sidgadBranch, Instant createdAt) {
        this.userId = userId;
        this.name = name;
        this.sidgadCategory = sidgadCategory;
        this.sidgadBranch = sidgadBranch;
        this.createdAt = createdAt;
    }

    void rename(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public String getSidgadCategory() { return sidgadCategory; }
    public String getSidgadBranch() { return sidgadBranch; }
}
