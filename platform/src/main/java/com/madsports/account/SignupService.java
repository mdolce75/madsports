package com.madsports.account;

import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.catalog.RosterService;
import com.madsports.catalog.RosterService.RosterPlayer;
import com.madsports.sidgad.model.Federation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Locale;

/**
 * Alta de usuarios. En RinkReports, el usuario de RinkReports ({@link Profile#REPORTS}): usuario, clave, email y plan,
 * sin club (elige federación, club, categoría y jugador en cada informe). Antes, RinkReports daba de alta club o
 * jugador, de la FPP o de la AMP (excluyentes), y la API los sigue aceptando. Para el jugador es obligatorio el
 * jugador de SIDGAD, elegido de la planilla de un equipo del club (categoría y rama en que juega; solo sirve para
 * encontrarlo: el informe busca al jugador en todas las categorías del club). El DT elige uno o más equipos de su
 * club, hasta los que permite su plan. El DT de otra federación (COACHOTR) no tiene club en SIDGAD: escribe su nombre.
 */
@Service
public class SignupService {

    /** El dato ya está registrado (usuario, email o jugador). */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /**
     * @param suscripcion código del plan elegido (CLUB_*, PLAYER_* o DT_*, según el perfil)
     * @param clubName    solo el DT de otra federación (COACHOTR): el nombre de su club; los demás lo eligen de SIDGAD
     */
    public record Signup(Profile profile, String username, String password, String email,
                           int clubId, String category, String branch, Integer playerId, String subscription,
                           String clubName) {
    }

    private final UserAccountRepository users;
    private final SubscriptionPlanRepository plans;
    private final CatalogService catalog;
    private final RosterService roster;
    private final PasswordEncoder encoder;
    private final Clock clock;
    /** ¿Se pueden crear cuentas de club en RinkReports? */
    private final boolean clubSignup;
    /** ¿Se puede elegir un plan pago del DT (el mensual)? Por ahora no: el DT empieza con el free. */
    private final boolean coachPaid;
    /** ¿Se puede elegir un plan pago del jugador (el mensual)? Por ahora no: el jugador empieza con el free. */
    private final boolean playerPaid;
    /** ¿Se pueden crear cuentas de jugador de la AMP? Por ahora no: solo de la FPP. */
    private final boolean playerAmp;
    /** ¿Se ofrece la AMP? Oculta hasta nuevo aviso: sin ella, ninguna cuenta nueva es de la AMP. */
    private final boolean amp;
    private final boolean clubPaid;

    public SignupService(UserAccountRepository users, SubscriptionPlanRepository plans, CatalogService catalog,
                           RosterService roster, PasswordEncoder encoder, Clock clock,
                           @Value("${madsports.signup.club:true}") boolean clubSignup,
                           @Value("${madsports.signup.coach-paid:false}") boolean coachPaid,
                           @Value("${madsports.signup.player-paid:false}") boolean playerPaid,
                           @Value("${madsports.signup.club-paid:false}") boolean clubPaid,
                           @Value("${madsports.signup.player-amp:false}") boolean playerAmp,
                           @Value("${madsports.signup.amp:false}") boolean amp) {
        this.playerAmp = playerAmp;
        this.amp = amp;
        this.clubSignup = clubSignup;
        this.coachPaid = coachPaid;
        this.playerPaid = playerPaid;
        this.clubPaid = clubPaid;
        this.users = users;
        this.plans = plans;
        this.catalog = catalog;
        this.roster = roster;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Transactional
    public UserAccount register(Signup r) {
        if (r.profile() == null) {
            throw new IllegalArgumentException("Elegí el perfil: Club, Jugador o DT, de la FPP o de la AMP.");
        }
        if (r.profile() != Profile.REPORTS && !r.profile().coach() && !r.profile().player() && !clubSignup) {
            throw new IllegalArgumentException("Las cuentas de club estarán disponibles próximamente. Por ahora podés "
                    + "crear tu cuenta de jugador.");
        }
        if (r.profile().federation() == Federation.AMP && !amp) {
            throw new IllegalArgumentException("Por ahora las cuentas son solo de la FPP.");
        }
        if (r.profile() == Profile.PLAYERAMP && !playerAmp) {
            throw new IllegalArgumentException("Las cuentas de jugador de la AMP estarán disponibles próximamente. Por "
                    + "ahora, las de la FPP.");
        }
        String username = r.username().trim().toLowerCase(Locale.ROOT);
        String email = r.email().trim();
        Product product = Product.de(r.profile());
        if (users.existsByUsernameAndProduct(username, product)) {
            throw new ConflictException("El usuario " + username + " ya existe en " + product.displayName() + ".");
        }
        String type = r.profile().userType();
        if (users.existsByEmailIgnoreCaseAndUserType(email, type)) {
            throw new ConflictException("Ya hay una cuenta " + switch (type) {
                case "PLAYER" -> "de jugador ";
                case "COACH" -> "de DT ";
                case "REPORTS" -> "";
                default -> "de club ";
            } + "en " + product.displayName() + " con ese email.");
        }
        SubscriptionPlan plan = plan(r);
        UserAccount u;
        if (r.profile() == Profile.REPORTS) {
            // Sin club: lo elige en cada informe.
            u = new UserAccount(username, encoder.encode(r.password()), email, null, null,
                    0, "", "", EnumSet.of(r.profile()), clock.instant());
        } else if (!r.profile().sidgad()) {
            // DT de otra federación: sin club de SIDGAD (id 0, sin sigla); sus equipos y jugadores, a mano.
            u = new UserAccount(username, encoder.encode(r.password()), email, null, null,
                    0, ownClubName(r.clubName()), "", EnumSet.of(r.profile()), clock.instant());
        } else {
            if (r.clubId() <= 0) {
                throw new IllegalArgumentException("Elegí el club.");
            }
            int year = catalog.defaultYear();
            Federation fed = r.profile().federation();
            ClubView club = catalog.club(fed, r.clubId(), year);
            if (r.profile().player()) {
                u = registerPlayer(r, username, email, club, year, fed);
            } else {
                // Club o DT: sin nombre ni apellido (el registro no los pide); el DT carga después sus equipos y
                // jugadores, en la sección Equipo.
                u = new UserAccount(username, encoder.encode(r.password()), email, null, null,
                        club.id(), club.name(), club.code(), EnumSet.of(r.profile()), clock.instant());
            }
        }
        u.subscribe(plan, LocalDate.now(clock));
        return users.save(u);
    }

    /** El club que escribe el DT de otra federación: obligatorio, con los espacios normalizados. */
    private static String ownClubName(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Escribí el nombre de tu club.");
        }
        if (n.length() > 80) {
            throw new IllegalArgumentException("El nombre del club es muy largo (hasta 80 letras).");
        }
        return n;
    }

    /** Plan elegido: obligatorio y del perfil (CLUB_* para clubes, PLAYER_* para jugadores, DT_* para DT). */
    private SubscriptionPlan plan(Signup r) {
        if (r.subscription() == null || r.subscription().isBlank()) {
            throw new IllegalArgumentException("Elegí un plan de suscripción.");
        }
        SubscriptionPlan plan = plans.findByCode(r.subscription().trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("Plan de suscripción inválido: " + r.subscription() + "."));
        if (!plan.forProfile(r.profile())) {
            throw new IllegalArgumentException("El plan " + plan.getName() + " es para " + plan.audience() + ".");
        }
        if (!plan.isValid(LocalDate.now(clock))) {
            throw new IllegalArgumentException("El plan " + plan.getName() + " ya no está disponible.");
        }
        if (!available(plan)) {
            String free = plans.findByUserTypeOrderByPriceUsdAscIdAsc(plan.getUserType()).stream()
                    .filter(p -> p.getPriceUsd().signum() == 0).map(SubscriptionPlan::getName).findFirst().orElse("free");
            throw new IllegalArgumentException("El plan " + plan.getName() + " estará disponible próximamente. "
                    + "Por ahora, empezá con el plan " + free + ".");
        }
        return plan;
    }

    /**
     * ¿Se puede elegir el plan? Los gratuitos, siempre (mientras estén vigentes); los pagos del DT, con
     * {@code madsports.signup.coach-paid}, y los del jugador, con {@code madsports.signup.player-paid}: hasta
     * que se habilite el cobro figuran como "Próximamente".
     */
    /**
     * Qué perfiles se pueden registrar hoy (las cuentas de club, la AMP y sus jugadores, según la configuración).
     */
    public record SignupOptions(boolean club, boolean amp, boolean playerAmp) {
    }

    public SignupOptions options() {
        return new SignupOptions(clubSignup, amp, amp && playerAmp);
    }

    public boolean available(SubscriptionPlan plan) {
        if (plan.getPriceUsd().signum() == 0) {
            return true;
        }
        return switch (plan.getUserType()) {
            case "COACH" -> coachPaid;
            case "PLAYER" -> playerPaid;
            case "CLUB" -> clubPaid;
            default -> true;
        };
    }

    /** Los planes pagos de RinkReports (jugador y club) que todavía no se venden: "Precio a definir". */
    public boolean priceToBeDefined(SubscriptionPlan plan) {
        return !available(plan) && !"COACH".equals(plan.getUserType());
    }

    /** Jugador: el nombre y el apellido son los de SIDGAD (el jugador elegido de la planilla de su equipo). */
    private UserAccount registerPlayer(Signup r, String username, String email, ClubView club, int year, Federation fed) {
        if (r.playerId() == null || r.playerId() <= 0) {
            throw new IllegalArgumentException("Elegí tu nombre en la lista de jugadores del club.");
        }
        if (users.existsByPlayerId(r.playerId())) {
            throw new ConflictException("Ese jugador ya tiene un usuario registrado.");
        }
        if (r.category() == null || r.category().isBlank() || r.branch() == null || r.branch().isBlank()) {
            throw new IllegalArgumentException("Elegí la categoría en que jugás.");
        }
        RosterPlayer player = roster.find(fed, club.id(), year, r.category(), r.branch(), r.playerId())
                .orElseThrow(() -> new IllegalArgumentException("El jugador elegido no figura en " + club.name() + "."));
        UserAccount u = new UserAccount(username, encoder.encode(r.password()), email, player.firstName(), player.lastName(),
                club.id(), club.name(), club.code(), EnumSet.of(r.profile()), clock.instant());
        u.linkPlayer(player.id(), player.name(),
                CatalogService.CategoryOption.parse(r.category()).level(), r.branch().trim().toUpperCase(Locale.ROOT));
        return u;
    }
}
