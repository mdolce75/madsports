package com.madsports.sidgad.tables;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Un jugador de SIDGAD (tabla {@code sidgad_player}); el id es el id_player, con el último nombre visto en un acta. */
@Entity
@Table(name = "sidgad_player")
public class SidgadPlayer {

    @Id
    private Integer id;
    private String name;
    private String surname;
    private Instant updatedAt;

    protected SidgadPlayer() {
    }

    SidgadPlayer(int id) {
        this.id = id;
    }

    void update(String name, String surname, Instant now) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (surname != null && !surname.isBlank()) {
            this.surname = surname;
        }
        this.updatedAt = now;
    }

    public Integer getId() { return id; }
    public String getName() { return name; }
    public String getSurname() { return surname; }
    public Instant getUpdatedAt() { return updatedAt; }
}
