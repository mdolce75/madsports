package com.rinkmetrics.account;

import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.sidgad.model.Federacion;
import com.rinkmetrics.catalog.RosterService;
import com.rinkmetrics.catalog.RosterService.RosterPlayer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
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
     */
    public record Registro(Perfil perfil, String usuario, String clave, String email,
                           int clubId, String categoria, String rama, Integer jugadorId, String suscripcion) {
    }

    private final UsuarioRepository usuarios;
    private final SuscripcionTypeRepository planes;
    private final CatalogService catalog;
    private final RosterService roster;
    private final PasswordEncoder encoder;
    private final Clock clock;
    /** ¿Se pueden crear cuentas de club en RinkReports? */
    private final boolean altaClub;
    /** ¿Se puede elegir un plan pago del DT (el mensual)? Por ahora no: el DT empieza con el free. */
    private final boolean dtPago;
    /** ¿Se puede elegir un plan pago del jugador (el mensual)? Por ahora no: el jugador empieza con el free. */
    private final boolean jugadorPago;
    /** ¿Se pueden crear cuentas de jugador de la AMP? Por ahora no: solo de la FPP. */
    private final boolean jugadorAmp;
    private final boolean clubPago;

    public RegistroService(UsuarioRepository usuarios, SuscripcionTypeRepository planes, CatalogService catalog,
                           RosterService roster, PasswordEncoder encoder, Clock clock,
                           @Value("${rinkmetrics.registro.club:true}") boolean altaClub,
                           @Value("${rinkmetrics.registro.dt-pago:false}") boolean dtPago,
                           @Value("${rinkmetrics.registro.jugador-pago:false}") boolean jugadorPago,
                           @Value("${rinkmetrics.registro.club-pago:false}") boolean clubPago,
                           @Value("${rinkmetrics.registro.jugador-amp:false}") boolean jugadorAmp) {
        this.jugadorAmp = jugadorAmp;
        this.altaClub = altaClub;
        this.dtPago = dtPago;
        this.jugadorPago = jugadorPago;
        this.clubPago = clubPago;
        this.usuarios = usuarios;
        this.planes = planes;
        this.catalog = catalog;
        this.roster = roster;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Transactional
    public Usuario registrar(Registro r) {
        if (r.perfil() == null) {
            throw new IllegalArgumentException("Elegí el perfil: Club, Jugador o DT, de la FPP o de la AMP.");
        }
        if (!r.perfil().dt() && !r.perfil().player() && !altaClub) {
            throw new IllegalArgumentException("Las cuentas de club estarán disponibles próximamente. Por ahora podés "
                    + "crear tu cuenta de jugador.");
        }
        if (r.perfil() == Perfil.PLAYERAMP && !jugadorAmp) {
            throw new IllegalArgumentException("Las cuentas de jugador de la AMP estarán disponibles próximamente. Por "
                    + "ahora, las de la FPP.");
        }
        String usuario = r.usuario().trim().toLowerCase(Locale.ROOT);
        String email = r.email().trim();
        if (usuarios.existsByUsuario(usuario)) {
            throw new ConflictException("El usuario " + usuario + " ya existe.");
        }
        Producto producto = Producto.de(r.perfil());
        if (usuarios.existsByEmailIgnoreCaseAndProducto(email, producto)) {
            throw new ConflictException("Ya hay una cuenta de " + producto.nombre() + " con ese email.");
        }
        SuscripcionType plan = plan(r);
        int year = catalog.defaultYear();
        Federacion fed = r.perfil().federacion();
        ClubView club = catalog.club(fed, r.clubId(), year);

        Usuario u;
        if (r.perfil().player()) {
            u = registrarJugador(r, usuario, email, club, year, fed);
        } else if (r.perfil().dt()) {
            // DT: sin nombre ni apellido; sus equipos y jugadores los carga después, en la sección Equipo.
            u = new Usuario(usuario, encoder.encode(r.clave()), email, null, null,
                    club.id(), club.name(), club.code(), EnumSet.of(r.perfil()), clock.instant());
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
        if (!plan.vigente(LocalDate.now(clock))) {
            throw new IllegalArgumentException("El plan " + plan.getNombre() + " ya no está disponible.");
        }
        if (!disponible(plan)) {
            String gratis = planes.findByTipoUsuarioOrderByPrecioUsdAscIdAsc(plan.getTipoUsuario()).stream()
                    .filter(p -> p.getPrecioUsd().signum() == 0).map(SuscripcionType::getNombre).findFirst().orElse("free");
            throw new IllegalArgumentException("El plan " + plan.getNombre() + " estará disponible próximamente. "
                    + "Por ahora, empezá con el plan " + gratis + ".");
        }
        return plan;
    }

    /**
     * ¿Se puede elegir el plan? Los gratuitos, siempre (mientras estén vigentes); los pagos del DT, con
     * {@code rinkmetrics.registro.dt-pago}, y los del jugador, con {@code rinkmetrics.registro.jugador-pago}: hasta
     * que se habilite el cobro figuran como "Próximamente".
     */
    /** Qué perfiles se pueden registrar hoy (las cuentas de club y los jugadores de la AMP, según la configuración). */
    public record Opciones(boolean club, boolean jugadorAmp) {
    }

    public Opciones opciones() {
        return new Opciones(altaClub, jugadorAmp);
    }

    public boolean disponible(SuscripcionType plan) {
        if (plan.getPrecioUsd().signum() == 0) {
            return true;
        }
        return switch (plan.getTipoUsuario()) {
            case "DT" -> dtPago;
            case "PLAYER" -> jugadorPago;
            case "CLUB" -> clubPago;
            default -> true;
        };
    }

    /** Los planes pagos de RinkReports (jugador y club) que todavía no se venden: "Precio a definir". */
    public boolean precioADefinir(SuscripcionType plan) {
        return !disponible(plan) && !"DT".equals(plan.getTipoUsuario());
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
