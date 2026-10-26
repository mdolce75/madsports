package com.madsports.coach;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Un jugador del DT (tabla {@code jugador_dt}), en uno o más de sus equipos ({@code jugador_equipo_dt}).
 * {@code playerId} es el id con el que lo nombran las acciones: el de SIDGAD si vino del plantel de una categoría del
 * club, o uno propio (desde {@link #FIRST_OWN_ID}) si lo cargó el DT. Dar de baja lo deja inactivo: sus acciones
 * quedan. En {@code pending} están los equipos en cuyo plantel de SIDGAD figura pero el DT no lo incluyó
 * ({@code jugador_pendiente_dt}): ahí no aparece para cargar acciones, y se puede sumar después.
 */
@Entity
@Table(name = "coach_player")
public class TeamPlayer {

    /** Primer id de los jugadores del DT (las acciones de antes usan ids de SIDGAD, que son menores). */
    static final int FIRST_OWN_ID = 900_000_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private int playerId;
    private String name;
    private String lastName;
    @Column(name = "shirt_number")
    private String number;
    private boolean goalkeeper;
    private boolean active = true;
    private Instant createdAt;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "coach_player_team", joinColumns = @JoinColumn(name = "coach_player_id"))
    @Column(name = "team_id")
    private Set<Long> teams = new HashSet<>();
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "coach_player_pending_team", joinColumns = @JoinColumn(name = "coach_player_id"))
    @Column(name = "team_id")
    private Set<Long> pending = new HashSet<>();

    protected TeamPlayer() {
    }

    TeamPlayer(Long userId, int playerId, String name, String lastName, String number, boolean goalkeeper,
              Collection<Long> teams, Instant createdAt) {
        this.userId = userId;
        this.playerId = playerId;
        this.name = name;
        this.lastName = lastName;
        this.number = number;
        this.goalkeeper = goalkeeper;
        this.teams = new HashSet<>(teams);
        this.createdAt = createdAt;
    }

    void update(String name, String lastName, String number, boolean goalkeeper, Collection<Long> teams) {
        this.name = name;
        this.lastName = lastName;
        this.number = number;
        this.goalkeeper = goalkeeper;
        this.teams = new HashSet<>(teams);
        pending.removeAll(this.teams);
    }

    void deactivate() {
        active = false;
    }

    /** Vuelve a estar activo (un jugador dado de baja que vuelve con el plantel de SIDGAD de otro equipo). */
    void reactivate() {
        active = true;
    }

    void removeFrom(Long teamId) {
        teams.remove(teamId);
        pending.remove(teamId);
    }

    /** Lo suma al equipo (deja de estar "no incluido" ahí). */
    void includeIn(Long teamId) {
        pending.remove(teamId);
        teams.add(teamId);
    }

    /** Lo saca del equipo: queda "no incluido" ahí, para volver a sumarlo (destildado en la lista del equipo). */
    void excludeFrom(Long teamId) {
        if (teams.remove(teamId)) {
            pending.add(teamId);
        }
    }

    /** Figura en el plantel de SIDGAD del equipo, pero el DT no lo incluyó. */
    void leavePendingIn(Long teamId) {
        if (!teams.contains(teamId)) {
            pending.add(teamId);
        }
    }

    public boolean playsIn(Long teamId) {
        return teams.contains(teamId);
    }

    /** Nombre y apellido ("Juan Pérez"). */
    public String fullName() {
        return (name + " " + lastName).trim();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public int getPlayerId() { return playerId; }
    public String getName() { return name; }
    public String getLastName() { return lastName; }
    public String getNumber() { return number; }
    public boolean isGoalkeeper() { return goalkeeper; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<Long> getTeams() { return Set.copyOf(teams); }
    public Set<Long> getPending() { return Set.copyOf(pending); }
}
