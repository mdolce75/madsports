package com.madsports.account;

import com.madsports.sidgad.model.Federation;

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
import jakarta.persistence.ManyToOne;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

@Entity
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Siempre en minúsculas: el login no distingue mayúsculas. */
    private String username;
    /** Hash BCrypt. */
    private String password;
    private String email;
    private String name;
    private String lastName;
    private int clubId;
    private String clubName;
    private String clubCode;
    /** id_player de SIDGAD (perfiles de jugador). */
    private Integer playerId;
    private String playerName;
    /** Categoría y rama del jugador (definen en qué categorías puede jugar; ver Eligibility). */
    private String category;
    private String branch;
    private boolean emailVerified;
    private Instant createdAt;
    /** Producto de la cuenta (sale del perfil): RinkReports para Club y Jugador, RinkMetrics para el DT. */
    @Enumerated(EnumType.STRING)
    private Product product;

    /** Suscripción comprada (null si todavía no tiene) y su vigencia. */
    @ManyToOne
    @JoinColumn(name = "subscription_plan_id")
    private SubscriptionPlan subscriptionPlan;
    private LocalDate subscriptionFrom;
    private LocalDate subscriptionTo;

    /** Informes pedidos por el usuario (club o jugador); los de ejemplo no cuentan. */
    private int reportsRequested;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_profile", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "profile")
    private Set<Profile> profiles = EnumSet.noneOf(Profile.class);

    protected UserAccount() {
    }

    public UserAccount(String username, String password, String email, String name, String lastName,
                   int clubId, String clubName, String clubCode, Set<Profile> profiles, Instant createdAt) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.name = name;
        this.lastName = lastName;
        this.clubId = clubId;
        this.clubName = clubName;
        this.clubCode = clubCode;
        this.profiles = EnumSet.copyOf(profiles);
        this.createdAt = createdAt;
        this.product = Product.de(profiles.iterator().next());
    }

    /** Datos del jugador de SIDGAD vinculado (perfiles de jugador). */
    public void linkPlayer(int playerId, String playerName, String category, String branch) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.category = category;
        this.branch = branch;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getLastName() { return lastName; }
    public int getClubId() { return clubId; }
    public String getClubName() { return clubName; }
    public String getClubCode() { return clubCode; }
    public Integer getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getCategory() { return category; }
    public String getBranch() { return branch; }
    public Instant getCreatedAt() { return createdAt; }
    public Product getProduct() { return product; }
    public boolean isEmailVerified() { return emailVerified; }
    public SubscriptionPlan getSubscriptionPlan() { return subscriptionPlan; }
    public LocalDate getSubscriptionFrom() { return subscriptionFrom; }
    public LocalDate getSubscriptionTo() { return subscriptionTo; }
    public int getReportsRequested() { return reportsRequested; }

    /**
     * Asigna la suscripción desde una fecha; el fin sale de la duración del tipo.
     *
     * @throws IllegalArgumentException si el tipo no corresponde al usuario (PLAYER_* para jugadores, CLUB_* para
     *                                  clubes, DT_* para DT)
     */
    public void subscribe(SubscriptionPlan kind, LocalDate from) {
        if (profiles.stream().noneMatch(kind::forProfile)) {
            throw new IllegalArgumentException("La suscripción " + kind.getCode() + " es para "
                    + kind.audience() + ".");
        }
        this.subscriptionPlan = kind;
        this.subscriptionFrom = from;
        this.subscriptionTo = kind.to(from);
    }

    public void verifyEmail() {
        this.emailVerified = true;
    }

    /** @param hash clave ya codificada (BCrypt) */
    public void changePassword(String hash) {
        this.password = hash;
    }
    public Set<Profile> getProfiles() { return Set.copyOf(profiles); }

    public boolean has(Profile profile) {
        return profiles.contains(profile);
    }

    /** Federación de sus perfiles (FPP o AMP); FPP si no tiene ninguno de esas federaciones. */
    public Federation federation() {
        return profiles.stream().map(Profile::federation).filter(java.util.Objects::nonNull).findFirst()
                .orElse(Federation.FPP);
    }

    /** ¿Tiene un perfil de jugador (PLAYERFPP, PLAYERAMP...)? */
    public boolean isPlayer() {
        return profiles.stream().anyMatch(Profile::player);
    }

    /** ¿Tiene un perfil de DT (DTFPP, DTAMP)? */
    public boolean isCoach() {
        return profiles.stream().anyMatch(Profile::coach);
    }
}
