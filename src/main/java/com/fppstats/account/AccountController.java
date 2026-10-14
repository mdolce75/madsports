package com.fppstats.account;

import com.fppstats.account.RegistroService.ConflictException;
import com.fppstats.account.RegistroService.Registro;
import com.fppstats.catalog.CatalogService;
import com.fppstats.config.SecurityConfig;
import com.fppstats.player.RosterService;
import com.fppstats.player.RosterService.RosterPlayer;
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
            Integer jugadorId) {
    }

    public record SessionView(boolean autenticado, String usuario, String nombre, String apellido, String email,
                              List<String> perfiles, Map<String, Object> club, Map<String, Object> jugador,
                              String inicio) {

        static final SessionView ANONYMOUS = new SessionView(false, null, null, null, null, List.of(), null, null,
                "/");
    }

    private final RegistroService registro;
    private final UsuarioRepository usuarios;
    private final RosterService roster;
    private final CatalogService catalog;

    public AccountController(RegistroService registro, UsuarioRepository usuarios, RosterService roster,
                             CatalogService catalog) {
        this.registro = registro;
        this.usuarios = usuarios;
        this.roster = roster;
        this.catalog = catalog;
    }

    @PostMapping("/registro")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> registrar(@Valid @RequestBody RegistroRequest req) {
        Usuario u = registro.registrar(new Registro(req.perfil(), req.usuario(), req.clave(), req.email(), req.clubId(), req.categoria(), req.rama(), req.jugadorId()));
        return Map.of("usuario", u.getUsuario());
    }

    /**
     * Jugadores de un equipo del club en el año en curso, según las actas de sus partidos.
     *
     * @param categoria opción de categoría del club ("INFANTIL" o "INFANTIL:B")
     * @param rama      MASCULINO o FEMENINO
     */
    @GetMapping("/registro/jugadores")
    public List<RosterPlayer> jugadores(@RequestParam int clubId, @RequestParam String categoria,
                                        @RequestParam String rama) {
        return roster.players(clubId, catalog.defaultYear(), categoria, rama);
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
                        SecurityConfig.home(auth.getAuthorities())))
                .orElse(SessionView.ANONYMOUS);
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
