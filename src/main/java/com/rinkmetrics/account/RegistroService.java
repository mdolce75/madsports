package com.rinkmetrics.account;

import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.sidgad.model.Federacion;
import com.rinkmetrics.player.RosterService;
import com.rinkmetrics.player.RosterService.RosterPlayer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Alta de usuarios: club, jugador o DT, de la FPP o de la AMP (excluyentes). Para el jugador es obligatorio el
 * jugador de SIDGAD, elegido de la planilla de un equipo del club (categoría y rama en que juega; solo sirve para
 * encontrarlo: el informe busca al jugador en todas las categorías del club). El DT elige uno o más equipos de su
 * club, hasta los que permite su plan.
 */
@Service
public class RegistroService {

    /** El dato ya está registrado (usuario, email o jugador). */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /**
     * @param suscripcion código del plan elegido (CLUB_*, PLAYER_* o DT_*, según el perfil)
     * @param equipos     equipos del DT (categoría con su letra y rama); vacío en los demás perfiles
     */
    public record Registro(Perfil perfil, String usuario, String clave, String email,
                           int clubId, String categoria, String rama, Integer jugadorId, String suscripcion,
                           List<EquipoDt> equipos) {

        public Registro(Perfil perfil, String usuario, String clave, String email,
                        int clubId, String categoria, String rama, Integer jugadorId, String suscripcion) {
            this(perfil, usuario, clave, email, clubId, categoria, rama, jugadorId, suscripcion, List.of());
        }
    }

    private final UsuarioRepository usuarios;
    private final SuscripcionTypeRepository planes;
    private final CatalogService catalog;
    private final RosterService roster;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public RegistroService(UsuarioRepository usuarios, SuscripcionTypeRepository planes, CatalogService catalog,
                           RosterService roster, PasswordEncoder encoder, Clock clock) {
        this.usuarios = usuarios;
        this.planes = planes;
        this.catalog = catalog;
        this.roster = roster;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Transactional
    public Usuario registrar(Registro r) {
        if (r.perfil() == null || !r.perfil().registrable()) {
            throw new IllegalArgumentException("Elegí el perfil: Club, Jugador o DT, de la FPP o de la AMP.");
        }
        String usuario = r.usuario().trim().toLowerCase(Locale.ROOT);
        String email = r.email().trim();
        if (usuarios.existsByUsuario(usuario)) {
            throw new ConflictException("El usuario " + usuario + " ya existe.");
        }
        if (usuarios.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Ya hay un usuario registrado con ese email.");
        }
        SuscripcionType plan = plan(r);
        int year = catalog.defaultYear();
        Federacion fed = r.perfil().federacion();
        ClubView club = catalog.club(fed, r.clubId(), year);

        Usuario u;
        if (r.perfil().player()) {
            u = registrarJugador(r, usuario, email, club, year, fed);
        } else if (r.perfil().dt()) {
            // DT: sin nombre ni apellido, con sus equipos del club.
            u = new Usuario(usuario, encoder.encode(r.clave()), email, null, null,
                    club.id(), club.name(), club.code(), EnumSet.of(r.perfil()), clock.instant());
            u.asignarEquiposDt(equiposDt(r.equipos(), club, plan));
        } else {
            // Club: sin nombre ni apellido (el registro no los pide).
            u = new Usuario(usuario, encoder.encode(r.clave()), email, null, null,
                    club.id(), club.name(), club.code(), EnumSet.of(r.perfil()), clock.instant());
        }
        u.suscribir(plan, LocalDate.now(clock));
        return usuarios.save(u);
    }

    /** Plan elegido: obligatorio y del perfil (CLUB_* para clubes, PLAYER_* para jugadores, DT_* para DT). */
    private SuscripcionType plan(Registro r) {
        if (r.suscripcion() == null || r.suscripcion().isBlank()) {
            throw new IllegalArgumentException("Elegí un plan de suscripción.");
        }
        SuscripcionType plan = planes.findByCodigo(r.suscripcion().trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("Plan de suscripción inválido: " + r.suscripcion() + "."));
        if (!plan.paraPerfil(r.perfil())) {
            throw new IllegalArgumentException("El plan " + plan.getNombre() + " es para " + plan.destinatarios() + ".");
        }
        return plan;
    }

    /**
     * Equipos del DT: al menos uno y como máximo los del plan, sin repetir, y cada uno un equipo del club en la
     * federación (categoría con su letra, como las ofrece el catálogo, y una rama en que existe).
     */
    static List<EquipoDt> equiposDt(List<EquipoDt> pedidos, ClubView club, SuscripcionType plan) {
        List<EquipoDt> equipos = (pedidos == null ? List.<EquipoDt>of() : pedidos).stream()
                .filter(e -> e != null && e.categoria() != null && !e.categoria().isBlank()
                        && e.rama() != null && !e.rama().isBlank())
                .map(e -> new EquipoDt(e.categoria().trim().toUpperCase(Locale.ROOT), e.rama().trim().toUpperCase(Locale.ROOT)))
                .distinct()
                .toList();
        if (equipos.isEmpty()) {
            throw new IllegalArgumentException("Elegí el equipo que dirigís.");
        }
        if (equipos.size() > plan.getEquiposMax()) {
            throw new IllegalArgumentException("El plan " + plan.getNombre() + " permite hasta "
                    + plan.getEquiposMax() + (plan.getEquiposMax() == 1 ? " equipo." : " equipos."));
        }
        for (EquipoDt e : equipos) {
            boolean existe = club.categories().stream()
                    .anyMatch(l -> l.key().equals(e.categoria()) && l.branches().contains(e.rama()));
            if (!existe) {
                throw new IllegalArgumentException(club.name() + " no tiene equipo en " + e.categoria() + " "
                        + e.rama() + ".");
            }
        }
        return equipos;
    }

    /** Jugador: el nombre y el apellido son los de SIDGAD (el jugador elegido de la planilla de su equipo). */
    private Usuario registrarJugador(Registro r, String usuario, String email, ClubView club, int year, Federacion fed) {
        if (r.jugadorId() == null || r.jugadorId() <= 0) {
            throw new IllegalArgumentException("Elegí tu nombre en la lista de jugadores del club.");
        }
        if (usuarios.existsByJugadorId(r.jugadorId())) {
            throw new ConflictException("Ese jugador ya tiene un usuario registrado.");
        }
        if (r.categoria() == null || r.categoria().isBlank() || r.rama() == null || r.rama().isBlank()) {
            throw new IllegalArgumentException("Elegí la categoría en que jugás.");
        }
        RosterPlayer jugador = roster.find(fed, club.id(), year, r.categoria(), r.rama(), r.jugadorId())
                .orElseThrow(() -> new IllegalArgumentException("El jugador elegido no figura en " + club.name() + "."));
        Usuario u = new Usuario(usuario, encoder.encode(r.clave()), email, jugador.firstName(), jugador.lastName(),
                club.id(), club.name(), club.code(), EnumSet.of(r.perfil()), clock.instant());
        u.vincularJugador(jugador.id(), jugador.name(),
                CatalogService.CategoryOption.parse(r.categoria()).level(), r.rama().trim().toUpperCase(Locale.ROOT));
        return u;
    }
}
