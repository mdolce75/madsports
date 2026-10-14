package com.fppstats.account;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

@Entity
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Siempre en minúsculas: el login no distingue mayúsculas. */
    private String usuario;
    /** Hash BCrypt. */
    private String clave;
    private String email;
    private String nombre;
    private String apellido;
    private int clubId;
    private String clubNombre;
    private String clubCodigo;
    private String dni;
    /** id_player de SIDGAD (perfiles de jugador). */
    private Integer jugadorId;
    private String jugadorNombre;
    private Instant creado;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "usuario_perfil", joinColumns = @JoinColumn(name = "usuario_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "perfil")
    private Set<Perfil> perfiles = EnumSet.noneOf(Perfil.class);

    protected Usuario() {
    }

    public Usuario(String usuario, String clave, String email, String nombre, String apellido,
                   int clubId, String clubNombre, String clubCodigo, Set<Perfil> perfiles, Instant creado) {
        this.usuario = usuario;
        this.clave = clave;
        this.email = email;
        this.nombre = nombre;
        this.apellido = apellido;
        this.clubId = clubId;
        this.clubNombre = clubNombre;
        this.clubCodigo = clubCodigo;
        this.perfiles = EnumSet.copyOf(perfiles);
        this.creado = creado;
    }

    /** Datos del jugador de SIDGAD vinculado (perfiles de jugador). */
    public void vincularJugador(String dni, int jugadorId, String jugadorNombre) {
        this.dni = dni;
        this.jugadorId = jugadorId;
        this.jugadorNombre = jugadorNombre;
    }

    public Long getId() { return id; }
    public String getUsuario() { return usuario; }
    public String getClave() { return clave; }
    public String getEmail() { return email; }
    public String getNombre() { return nombre; }
    public String getApellido() { return apellido; }
    public int getClubId() { return clubId; }
    public String getClubNombre() { return clubNombre; }
    public String getClubCodigo() { return clubCodigo; }
    public String getDni() { return dni; }
    public Integer getJugadorId() { return jugadorId; }
    public String getJugadorNombre() { return jugadorNombre; }
    public Instant getCreado() { return creado; }
    public Set<Perfil> getPerfiles() { return Set.copyOf(perfiles); }

    public boolean tiene(Perfil perfil) {
        return perfiles.contains(perfil);
    }
}
