package com.rinkmetrics.account;

import com.rinkmetrics.account.RegistroService.ConflictException;
import com.rinkmetrics.account.RegistroService.Registro;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.sidgad.model.Federacion;
import com.rinkmetrics.config.SecurityConfig;
import com.rinkmetrics.player.RosterService;
import com.rinkmetrics.player.RosterService.RosterPlayer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
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

    public record RegistroRequest(
            @NotNull(message = "Elegí el perfil.") Perfil perfil,
            @NotBlank(message = "El usuario es obligatorio.")
            @Pattern(regexp = "[A-Za-z0-9._-]{3,30}", message = "El usuario lleva de 3 a 30 letras, números, punto, guion o guion bajo.")
            String usuario,
            @NotBlank(message = "La clave es obligatoria.")
            @Size(min = 8, max = 72, message = "La clave lleva entre 8 y 72 caracteres.")
            String clave,
            @NotBlank(message = "El email es obligatorio.") @Email(message = "El email no es válido.")
            @Size(max = 120) String email,
            @Positive(message = "Elegí el club.") int clubId,
            String categoria,
            String rama,
            Integer jugadorId,
            @NotBlank(message = "Elegí un plan de suscripción.") String suscripcion) {
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
    public record PlanView(String codigo, String nombre, BigDecimal precio, BigDecimal precioUsd, int informesPorMes,
                           BigDecimal dolar, LocalDate dolarFecha, int equiposMax, int partidosPorMes,
                           LocalDate vigenteHasta, boolean disponible,
                           boolean precioADefinir) {
    }

    /**
     * @param federacion sigla de la federación de sus perfiles (FPP, AMP)
     * @param producto   REPORTS o METRICS: el producto de la cuenta
     * @param informesMes informes pedidos este mes: {usados, limite (0 = sin límite), renueva}
     */
    public record SessionView(boolean autenticado, String usuario, String nombre, String apellido, String email,
                              List<String> perfiles, Map<String, Object> club, Map<String, Object> jugador,
                              String inicio, Map<String, Object> suscripcion, int informesPedidos,
                              String federacion, String producto, Map<String, Object> informesMes) {

        static final SessionView ANONYMOUS = new SessionView(false, null, null, null, null, List.of(), null, null,
                "/", null, 0, null, null, null);
    }

    private final RegistroService registro;
    private final UsuarioRepository usuarios;
    private final RosterService roster;
    private final CatalogService catalog;
    private final CuentaService cuenta;
    private final SuscripcionTypeRepository planes;
    private final Cotizacion cotizacion;
    private final Clock clock;
    private final CupoInformes cupo;

    public AccountController(RegistroService registro, UsuarioRepository usuarios, RosterService roster,
                             CatalogService catalog, CuentaService cuenta, SuscripcionTypeRepository planes,
                             Cotizacion cotizacion, Clock clock, CupoInformes cupo) {
        this.clock = clock;
        this.cupo = cupo;
        this.cuenta = cuenta;
        this.planes = planes;
        this.cotizacion = cotizacion;
        this.registro = registro;
        this.usuarios = usuarios;
        this.roster = roster;
        this.catalog = catalog;
    }

    /** El plan mensual del perfil (CLUBFPP, PLAYERAMP, DTFPP...), con el precio en pesos al dólar oficial del día. */
    @GetMapping("/suscripciones")
    public List<PlanView> planes(@RequestParam Perfil perfil) {
        Cotizacion.Valor dolar = cotizacion.actual();
        LocalDate hoy = LocalDate.now(clock);
        return planes.findByTipoUsuarioOrderByPrecioUsdAscIdAsc(perfil.tipoUsuario()).stream()
                .filter(p -> p.vigente(hoy))   // el DT free deja de ofrecerse después del 31/12/2026
                .map(p -> new PlanView(p.getCodigo(), p.getNombre(), Cotizacion.enPesos(p.getPrecioUsd(), dolar),
                        p.getPrecioUsd(), p.getInformesPorMes(), dolar.pesosPorDolar(), dolar.fecha(),
                        p.getEquiposMax(), p.getPartidosPorMes(), p.getVigenteHasta(), registro.disponible(p),
                        registro.precioADefinir(p)))
                .toList();
    }

    /** Alta: la cuenta queda sin confirmar y se manda el mail con el enlace para confirmarla. */
    @PostMapping("/registro")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> registrar(@Valid @RequestBody RegistroRequest req) {
        Usuario u = registro.registrar(new Registro(req.perfil(), req.usuario(), req.clave(), req.email(),
                req.clubId(), req.categoria(), req.rama(), req.jugadorId(), req.suscripcion()));
        cuenta.enviarVerificacion(u);
        return Map.of("usuario", u.getUsuario(), "email", u.getEmail());
    }

    /** @param usuario alternativa al email (para reenviar la confirmación desde el ingreso) */
    /** @param producto REPORTS o METRICS: el de la pantalla (el email puede tener una cuenta en cada producto) */
    public record EmailRequest(String email, String usuario, String producto) {
    }

    public record RestablecerRequest(String token, String clave) {
    }

    /** Enlace del mail de confirmación: confirma y lleva al ingreso. */
    @GetMapping("/verificar")
    public ResponseEntity<Void> verificar(@RequestParam(required = false) String token) {
        // Al ingreso del producto de la cuenta; si el enlace no sirve, no se sabe de qué producto es: al de RinkMetrics.
        String destino = cuenta.verificar(token).map(p -> p.ingreso() + "?verificado")
                .orElse("/login.html?verificacion=invalida");
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(destino)).build();
    }

    /** Siempre responde lo mismo, exista o no una cuenta con ese email. */
    @PostMapping("/verificacion/reenviar")
    public Map<String, String> reenviar(@RequestBody EmailRequest req) {
        cuenta.reenviarVerificacion(req.email(), req.usuario(), Producto.parse(req.producto()));
        return Map.of("detail", "Si hay una cuenta sin confirmar con ese email, te mandamos el enlace de nuevo.");
    }

    /** Siempre responde lo mismo, exista o no una cuenta con ese email. */
    @PostMapping("/clave/olvido")
    public Map<String, String> olvido(@RequestBody EmailRequest req) {
        cuenta.olvideClave(req.email(), Producto.parse(req.producto()));
        return Map.of("detail", "Si hay una cuenta con ese email, te mandamos un enlace para elegir una clave nueva.");
    }

    @PostMapping("/clave/restablecer")
    public Map<String, String> restablecer(@RequestBody RestablecerRequest req) {
        cuenta.restablecerClave(req.token(), req.clave());
        return Map.of("detail", "Listo: ya podés ingresar con tu clave nueva.");
    }

    /**
     * Jugadores de un equipo del club en el año en curso, según las actas de sus partidos.
     *
     * @param categoria opción de categoría del club ("INFANTIL" o "INFANTIL:B")
     * @param rama      MASCULINO o FEMENINO
     */
    @GetMapping("/registro/jugadores")
    public List<RosterPlayer> jugadores(@RequestParam int clubId, @RequestParam String categoria,
                                        @RequestParam String rama,
                                        @RequestParam(required = false) String federacion) {
        return roster.players(Federacion.parse(federacion), clubId, catalog.defaultYear(), categoria, rama);
    }

    /** Qué perfiles se pueden registrar hoy (las cuentas de club y los jugadores de la AMP se habilitan por configuración). */
    @GetMapping("/registro/opciones")
    public RegistroService.Opciones opciones() {
        return registro.opciones();
    }

    @GetMapping("/sesion")
    public SessionView sesion(Authentication auth) {
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return SessionView.ANONYMOUS;
        }
        return usuarios.findByUsuario(auth.getName())
                .map(u -> new SessionView(true, u.getUsuario(), u.getNombre(), u.getApellido(), u.getEmail(),
                        u.getPerfiles().stream().map(Enum::name).sorted().toList(),
                        Map.of("id", u.getClubId(), "nombre", u.getClubNombre(), "codigo", u.getClubCodigo()),
                        u.getJugadorId() == null ? null : Map.of("id", u.getJugadorId(), "nombre", u.getJugadorNombre(),
                                "categoria", Objects.toString(u.getCategoria(), ""), "rama", Objects.toString(u.getRama(), "")),
                        SecurityConfig.home(auth.getAuthorities()),
                        u.getSuscripcionType() == null ? null : Map.of(
                                "tipo", u.getSuscripcionType().getCodigo(),
                                "tipoUsuario", u.getSuscripcionType().getTipoUsuario(),
                                "nombre", u.getSuscripcionType().getNombre(),
                                "desde", u.getSuscripcionDesde().toString(),
                                "hasta", u.getSuscripcionHasta().toString(),
                                "equiposMax", u.getSuscripcionType().getEquiposMax(),
                                "partidosPorMes", u.getSuscripcionType().getPartidosPorMes()),
                        u.getInformesPedidos(), u.federacion().name(), u.getProducto().name(), informesMes(u)))
                .orElse(SessionView.ANONYMOUS);
    }

    private Map<String, Object> informesMes(Usuario u) {
        CupoInformes.Cupo c = cupo.cupo(u);
        return Map.of("usados", c.usados(), "limite", c.limite(), "renueva", c.renueva().toString());
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
