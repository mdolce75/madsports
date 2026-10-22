package com.madsports.coach;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un partido que el DT empezó a cargar (tabla {@code partido_creado_dt}): cuenta para el cupo del mes aunque después
 * elimine el partido.
 */
@Entity
@Table(name = "coach_started_match")
public class StartedMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private Instant createdAt;

    protected StartedMatch() {
    }

    StartedMatch(Long userId, Instant createdAt) {
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
}
