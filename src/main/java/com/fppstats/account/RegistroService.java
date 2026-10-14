package com.fppstats.account;

import com.fppstats.catalog.CatalogService;
import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.player.RosterService;
import com.fppstats.player.RosterService.RosterPlayer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Locale;

/**
 * Alta de usuarios. Por ahora se ofrecen dos perfiles, excluyentes: CLUBFPP o PLAYERFPP. Para PLAYERFPP son
 * obligatorio el jugador de SIDGAD, elegido de la planilla de un equipo del club (categoría y rama en que
 * juega; solo sirve para encontrarlo: el informe busca al jugador en todas las categorías del club).
 */
@Service
public class RegistroService {

    /** El dato ya está registrado (usuario, email o jugador). */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    public record Registro(Perfil perfil, String usuario, String clave, String email,
                           int clubId, String categoria, String rama, Integer jugadorId) {
    }

    private final UsuarioRepository usuarios;
    private final CatalogService catalog;
    private final RosterService roster;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public RegistroService(UsuarioRepository usuarios, CatalogService catalog, RosterService roster,
                           PasswordEncoder encoder, Clock clock) {
        this.usuarios = usuarios;
        this.catalog = catalog;
        this.roster = roster;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Transactional
    public Usuario registrar(Registro r) {
        if (r.perfil() == null || !r.perfil().registrable()) {
            throw new IllegalArgumentException("Elegí el perfil: Club FPP o Jugador FPP.");
        }
        String usuario = r.usuario().trim().toLowerCase(Locale.ROOT);
        String email = r.email().trim();
        if (usuarios.existsByUsuario(usuario)) {
            throw new ConflictException("El usuario " + usuario + " ya existe.");
        }
        if (usuarios.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Ya hay un usuario registrado con ese email.");
        }
        int year = catalog.defaultYear();
        ClubView club = catalog.club(r.clubId(), year);

        if (r.perfil().player()) {
            return registrarJugador(r, usuario, email, club, year);
        }
        // Club: sin nombre ni apellido (el registro no los pide).
        return usuarios.save(new Usuario(usuario, encoder.encode(r.clave()), email, null, null,
                club.id(), club.name(), club.code(), EnumSet.of(r.perfil()), clock.instant()));
    }

    /** Jugador: el nombre y el apellido son los de la FPP (el jugador elegido de la planilla de su equipo). */
    private Usuario registrarJugador(Registro r, String usuario, String email, ClubView club, int year) {
        if (r.jugadorId() == null || r.jugadorId() <= 0) {
            throw new IllegalArgumentException("Elegí tu nombre en la lista de jugadores del club.");
        }
        if (usuarios.existsByJugadorId(r.jugadorId())) {
            throw new ConflictException("Ese jugador ya tiene un usuario registrado.");
        }
        if (r.categoria() == null || r.categoria().isBlank() || r.rama() == null || r.rama().isBlank()) {
            throw new IllegalArgumentException("Elegí la categoría en que jugás.");
        }
        RosterPlayer jugador = roster.find(club.id(), year, r.categoria(), r.rama(), r.jugadorId())
                .orElseThrow(() -> new IllegalArgumentException("El jugador elegido no figura en " + club.name() + "."));
        Usuario u = new Usuario(usuario, encoder.encode(r.clave()), email, jugador.firstName(), jugador.lastName(),
                club.id(), club.name(), club.code(), EnumSet.of(r.perfil()), clock.instant());
        u.vincularJugador(jugador.id(), jugador.name(),
                CatalogService.CategoryOption.parse(r.categoria()).level(), r.rama().trim().toUpperCase(Locale.ROOT));
        return usuarios.save(u);
    }
}
