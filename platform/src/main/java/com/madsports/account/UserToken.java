package com.madsports.account;

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
public class UserToken {

    public enum Kind {
        /** Confirmar el email después del registro: vale 48 horas. */
        VERIFICATION,
        /** Elegir una clave nueva: vale 1 hora. */
        PASSWORD_RESET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private String hash;
    private Instant createdAt;
    private Instant expiresAt;
    private boolean used;

    protected UserToken() {
    }

    public UserToken(UserAccount user, Kind kind, String hash, Instant createdAt, Instant expiresAt) {
        this.user = user;
        this.kind = kind;
        this.hash = hash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** ¿Se puede usar ahora? (no usado y sin vencer) */
    public boolean isValid(Instant now) {
        return !used && now.isBefore(expiresAt);
    }

    public void use() {
        this.used = true;
    }

    public UserAccount getUser() { return user; }
    public Kind getKind() { return kind; }
    public Instant getCreatedAt() { return createdAt; }
}
