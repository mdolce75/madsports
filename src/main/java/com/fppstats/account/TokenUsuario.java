package com.fppstats.account;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.Instant;

/** Enlace de un solo uso enviado por mail (confirmar el email o elegir una clave nueva). Guarda el hash del token. */
@Entity
public class TokenUsuario {

    public enum Tipo {
        /** Confirmar el email después del registro: vale 48 horas. */
        VERIFICACION,
        /** Elegir una clave nueva: vale 1 hora. */
        RECUPERO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    private Tipo tipo;

    private String hash;
    private Instant creado;
    private Instant expira;
    private boolean usado;

    protected TokenUsuario() {
    }

    public TokenUsuario(Usuario usuario, Tipo tipo, String hash, Instant creado, Instant expira) {
        this.usuario = usuario;
        this.tipo = tipo;
        this.hash = hash;
        this.creado = creado;
        this.expira = expira;
    }

    /** ¿Se puede usar ahora? (no usado y sin vencer) */
    public boolean vigente(Instant ahora) {
        return !usado && ahora.isBefore(expira);
    }

    public void usar() {
        this.usado = true;
    }

    public Usuario getUsuario() { return usuario; }
    public Tipo getTipo() { return tipo; }
    public Instant getCreado() { return creado; }
}
