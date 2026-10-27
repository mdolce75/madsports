package com.madsports.account;

import com.madsports.account.SignupService.ConflictException;
import com.madsports.account.SignupService.Signup;
import com.madsports.catalog.CatalogService;
import com.madsports.sidgad.model.Federation;
import com.madsports.config.SecurityConfig;
import com.madsports.catalog.RosterService;
import com.madsports.catalog.RosterService.RosterPlayer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Registro, datos de la sesión y lista de jugadores de un club (para el registro de jugadores). */
@RestController
@RequestMapping("/api")
public class AccountController {

    public record SignupRequest(
            @NotNull(message = "Elegí el perfil.") Profile profile,
            @NotBlank(message = "El usuario es obligatorio.")
            @Pattern(regexp = "[A-Za-z0-9._-]{3,30}", message = "El usuario lleva de 3 a 30 letras, números, punto, guion o guion bajo.")
            String username,
            @NotBlank(message = "La clave es obligatoria.")
            @Size(min = 8, max = 72, message = "La clave lleva entre 8 y 72 caracteres.")
            String password,
            @NotBlank(message = "El email es obligatorio.") @Email(message = "El email no es válido.")
            @Size(max = 120) String email,
            @Positive(message = "Elegí el club.") int clubId,
            String category,
            String branch,
            Integer playerId,
            @NotBlank(message = "Elegí un plan de suscripción.") String subscription) {
    }

    /**
     * El plan mensual para mostrar en el registro, con el precio en pesos al tipo de cambio del día.
     *
     * @param precio     precio por mes, en pesos (redondeado hacia abajo a centenas)
     * @param precioUsd  precio base, en dólares
     * @param dolar      pesos por dólar con que se convirtió (oficial BNA, venta)
     * @param dolarFecha día de esa cotización
     */
    /**
     * @param partidosPorMes DT: partidos por mes (0 = sin límite)
     * @param vigenteHasta   último día de un plan con fecha fija de fin (el DT free); null en los mensuales
     * @param disponible     false si todavía no se puede elegir (el DT mensual: próximamente)
     */
    public record PlanView(String code, String name, BigDecimal price, BigDecimal priceUsd, int reportsPerMonth,
                           BigDecimal dollar, LocalDate dollarDate, int maxTeams, int matchesPerMonth,
                           LocalDate validUntil, boolean available,
                           boolean priceToBeDefined) {
    }

    /**
     * @param federacion sigla de la federación de sus perfiles (FPP, AMP)
     * @param producto   REPORTS o METRICS: el producto de la cuenta
     * @param informesMes informes pedidos este mes: {usados, limite (0 = sin límite), renueva}
     */
    public record SessionView(boolean authenticated, String username, String name, String lastName, String email,
                              List<String> profiles, Map<String, Object> club, Map<String, Object> player,
                              String home, Map<String, Object> subscription, int reportsRequested,
                              String federation, String product, Map<String, Object> monthlyReports) {

        static final SessionView ANONYMOUS = new SessionView(false, null, null, null, null, List.of(), null, null,
                "/", null, 0, null, null, null);
    }

    private final SignupService signup;
    private final UserAccountRepository users;
    private final RosterService roster;
    private final CatalogService catalog;
    private final AccountService account;
    private final SubscriptionPlanRepository plans;
    private final ExchangeRate exchangeRate;
    private final Clock clock;
    private final ObjectProvider<MonthlyReports> reports;

    public AccountController(SignupService signup, UserAccountRepository users, RosterService roster,
                             CatalogService catalog, AccountService account, SubscriptionPlanRepository plans,
                             ExchangeRate exchangeRate, Clock clock, ObjectProvider<MonthlyReports> reports) {
        this.clock = clock;
        this.reports = reports;
        this.account = account;
        this.plans = plans;
        this.exchangeRate = exchangeRate;
        this.signup = signup;
        this.users = users;
        this.roster = roster;
        this.catalog = catalog;
    }

    /** El plan mensual del perfil (CLUBFPP, PLAYERAMP, DTFPP...), con el precio en pesos al dólar oficial del día. */
    @GetMapping("/plans")
    public List<PlanView> plans(@RequestParam Profile profile) {
        ExchangeRate.Quote dollar = exchangeRate.current();
        LocalDate today = LocalDate.now(clock);
        return plans.findByUserTypeOrderByPriceUsdAscIdAsc(profile.userType()).stream()
                .filter(p -> p.isValid(today))   // el DT free deja de ofrecerse después del 31/12/2026
                .map(p -> new PlanView(p.getCode(), p.getName(), ExchangeRate.inPesos(p.getPriceUsd(), dollar),
                        p.getPriceUsd(), p.getReportsPerMonth(), dollar.pesosPerDollar(), dollar.date(),
                        p.getMaxTeams(), p.getMatchesPerMonth(), p.getValidUntil(), signup.available(p),
                        signup.priceToBeDefined(p)))
                .toList();
    }

    /** Alta: la cuenta queda sin confirmar y se manda el mail con el enlace para confirmarla. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(@Valid @RequestBody SignupRequest req) {
        UserAccount u = signup.register(new Signup(req.profile(), req.username(), req.password(), req.email(),
                req.clubId(), req.category(), req.branch(), req.playerId(), req.subscription()));
        account.sendVerification(u);
        account.notifySignup(u);
        return Map.of("username", u.getUsername(), "email", u.getEmail());
    }

    /** @param usuario alternativa al email (para reenviar la confirmación desde el ingreso) */
    /** @param producto REPORTS o METRICS: el de la pantalla (el email puede tener una cuenta en cada producto) */
    public record EmailRequest(String email, String username, String product) {
    }

    public record ResetPasswordRequest(String token, String password) {
    }

    /** Enlace del mail de confirmación: confirma y lleva al ingreso. */
    // /api/verificar: el enlace de los mails mandados antes de que la API pasara al inglés.
    @GetMapping({"/verify", "/verificar"})
    public ResponseEntity<Void> verify(@RequestParam(required = false) String token) {
        // Al ingreso del producto de la cuenta; si el enlace no sirve, no se sabe de qué producto es: al de RinkMetrics.
        String target = account.verify(token).map(p -> p.login() + "?verified")
                .orElse(Product.METRICS.login() + "?verification=invalid");
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(target)).build();
    }

    /** Siempre responde lo mismo, exista o no una cuenta con ese email. */
    @PostMapping("/verification/resend")
    public Map<String, String> resend(@RequestBody EmailRequest req) {
        account.resendVerification(req.email(), req.username(), Product.parse(req.product()));
        return Map.of("detail", "Si hay una cuenta sin confirmar con ese email, te mandamos el enlace de nuevo.");
    }

    /** Siempre responde lo mismo, exista o no una cuenta con ese email. */
    @PostMapping("/password/forgot")
    public Map<String, String> forgot(@RequestBody EmailRequest req) {
        account.forgotPassword(req.email(), Product.parse(req.product()));
        return Map.of("detail", "Si hay una cuenta con ese email, te mandamos un enlace para elegir una clave nueva.");
    }

    @PostMapping("/password/reset")
    public Map<String, String> reset(@RequestBody ResetPasswordRequest req) {
        account.resetPassword(req.token(), req.password());
        return Map.of("detail", "Listo: ya podés ingresar con tu clave nueva.");
    }

    /**
     * Jugadores de un equipo del club en el año en curso, según las actas de sus partidos.
     *
     * @param categoria opción de categoría del club ("INFANTIL" o "INFANTIL:B")
     * @param rama      MASCULINO o FEMENINO
     */
    @GetMapping("/signup/players")
    public List<RosterPlayer> players(@RequestParam int clubId, @RequestParam String category,
                                        @RequestParam String branch,
                                        @RequestParam(required = false) String federation) {
        return roster.players(Federation.parse(federation), clubId, catalog.defaultYear(), category, branch);
    }

    /** Qué perfiles se pueden registrar hoy (las cuentas de club y los jugadores de la AMP se habilitan por configuración). */
    @GetMapping("/signup/options")
    public SignupService.SignupOptions options() {
        return signup.options();
    }

    /**
     * La sesión, vista desde un producto: la de RinkReports es de RinkReports y la de RinkMetrics, de RinkMetrics. Con
     * {@code product}, una cuenta del otro producto se ve como sin sesión (cada pantalla mira solo la de su producto;
     * con un dominio por producto, además, cada uno tiene su cookie).
     *
     * @param product REPORTS o METRICS: el de la pantalla que pregunta; sin él, la cuenta sea del producto que sea
     */
    @GetMapping("/session")
    public SessionView session(Authentication auth, @RequestParam(required = false) String product) {
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return SessionView.ANONYMOUS;
        }
        Product asked = Product.parse(product);
        return users.findByPrincipal(auth.getName())
                .filter(u -> asked == null || u.getProduct() == asked)
                .map(u -> new SessionView(true, u.getUsername(), u.getName(), u.getLastName(), u.getEmail(),
                        u.getProfiles().stream().map(Enum::name).sorted().toList(),
                        Map.of("id", u.getClubId(), "name", u.getClubName(), "code", u.getClubCode()),
                        u.getPlayerId() == null ? null : Map.of("id", u.getPlayerId(), "name", u.getPlayerName(),
                                "category", Objects.toString(u.getCategory(), ""), "branch", Objects.toString(u.getBranch(), "")),
                        SecurityConfig.home(auth.getAuthorities()),
                        u.getSubscriptionPlan() == null ? null : Map.of(
                                "kind", u.getSubscriptionPlan().getCode(),
                                "userType", u.getSubscriptionPlan().getUserType(),
                                "name", u.getSubscriptionPlan().getName(),
                                "from", u.getSubscriptionFrom().toString(),
                                "to", u.getSubscriptionTo().toString(),
                                "maxTeams", u.getSubscriptionPlan().getMaxTeams(),
                                "matchesPerMonth", u.getSubscriptionPlan().getMatchesPerMonth()),
                        u.getReportsRequested(), u.federation().name(), u.getProduct().name(), monthlyReports(u)))
                .orElse(SessionView.ANONYMOUS);
    }

    /** Lo aporta RinkReports (su cupo de informes); sin él, la sesión no lo trae. */
    private Map<String, Object> monthlyReports(UserAccount u) {
        MonthlyReports reports = this.reports.getIfAvailable();
        return reports == null ? null : reports.de(u);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage()).distinct().reduce((a, b) -> a + " " + b).orElse("Datos inválidos.");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Dos altas simultáneas con el mismo dato: la base lo rechaza. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail duplicate(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "El usuario, el email o el jugador ya están registrados.");
    }
}
