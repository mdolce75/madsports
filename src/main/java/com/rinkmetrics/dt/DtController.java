package com.rinkmetrics.dt;

import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.account.UsuarioRepository;
import com.rinkmetrics.dt.DtService.AccionNueva;
import com.rinkmetrics.dt.DtService.AccionReloj;
import com.rinkmetrics.dt.DtService.NuevoPartido;
import com.rinkmetrics.dt.DtService.PartidoView;
import com.rinkmetrics.dt.DtService.Rival;
import com.rinkmetrics.player.RosterService.PlantelJugador;
import com.rinkmetrics.sidgad.SidgadClient.SidgadException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Métricas DT en vivo (perfiles DTFPP y DTAMP): rivales posibles, plantel, partidos, acciones y reloj. Cada DT ve solo
 * sus equipos y sus partidos; el partido de otro DT responde 404.
 */
@RestController
@RequestMapping("/api/dt")
public class DtController {

    /** @param segundos solo para AJUSTAR: segundos de tiempo jugado a sumar (o restar, si es negativo) */
    public record RelojRequest(AccionReloj accion, Integer segundos) {
    }

    private final DtService dt;
    private final DtInformeService informes;
    private final UsuarioRepository usuarios;

    public DtController(DtService dt, DtInformeService informes, UsuarioRepository usuarios) {
        this.dt = dt;
        this.informes = informes;
        this.usuarios = usuarios;
    }

    @GetMapping("/rivales")
    public List<Rival> rivales(@RequestParam(required = false) String categoria,
                               @RequestParam(required = false) String rama, Authentication auth) {
        return dt.rivales(usuario(auth), categoria, rama);
    }

    /** Acciones de todos los partidos del equipo, para las fichas de los jugadores. */
    @GetMapping("/acciones")
    public List<DtService.AccionDeEquipo> acciones(@RequestParam(required = false) String categoria,
                                                   @RequestParam(required = false) String rama, Authentication auth) {
        return dt.accionesDelEquipo(usuario(auth), categoria, rama);
    }

    @GetMapping("/plantel")
    public List<PlantelJugador> plantel(@RequestParam(required = false) String categoria,
                                        @RequestParam(required = false) String rama, Authentication auth) {
        return dt.plantel(usuario(auth), categoria, rama);
    }

    @PostMapping("/partidos")
    public ResponseEntity<PartidoView> crear(@RequestBody NuevoPartido req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dt.crear(usuario(auth), req));
    }

    @GetMapping("/partidos")
    public List<PartidoView> lista(Authentication auth) {
        return dt.lista(usuario(auth));
    }

    @GetMapping("/partidos/{id}")
    public PartidoView partido(@PathVariable Long id, Authentication auth) {
        return dt.partido(usuario(auth), id);
    }

    @PostMapping("/partidos/{id}/acciones")
    public PartidoView acciones(@PathVariable Long id, @RequestBody List<AccionNueva> acciones, Authentication auth) {
        return dt.agregar(usuario(auth), id, acciones);
    }

    @DeleteMapping("/partidos/{id}/acciones/{accionId}")
    public PartidoView borrar(@PathVariable Long id, @PathVariable String accionId, Authentication auth) {
        return dt.borrar(usuario(auth), id, accionId);
    }

    @PostMapping("/partidos/{id}/reloj")
    public PartidoView reloj(@PathVariable Long id, @RequestBody RelojRequest req, Authentication auth) {
        return dt.reloj(usuario(auth), id, req.accion(), req.segundos());
    }

    @PostMapping("/partidos/{id}/cerrar")
    public PartidoView cerrar(@PathVariable Long id, Authentication auth) {
        return dt.cerrar(usuario(auth), id);
    }

    @PostMapping("/partidos/{id}/reabrir")
    public PartidoView reabrir(@PathVariable Long id, Authentication auth) {
        return dt.reabrir(usuario(auth), id);
    }

    /** Informe del partido: del equipo o, con {@code jugador} ("j" + id, o "m" + nombre), de ese jugador. */
    @GetMapping("/partidos/{id}/informe")
    public InformeDt informe(@PathVariable Long id, @RequestParam(required = false) String jugador,
                             Authentication auth) {
        return informes.partido(usuario(auth), id, jugador);
    }

    @GetMapping("/partidos/{id}/informe.pdf")
    public ResponseEntity<byte[]> informePdf(@PathVariable Long id, @RequestParam(required = false) String jugador,
                                             Authentication auth) {
        return pdf(informes.pdf(informes.partido(usuario(auth), id, jugador)));
    }

    /** Informe de los partidos finalizados del equipo en un mes, bimestre, semestre o campeonato. */
    @GetMapping("/informes")
    public InformeDt informePeriodo(@RequestParam(required = false) String categoria,
                                    @RequestParam(required = false) String rama, @RequestParam Periodo periodo,
                                    @RequestParam(required = false) String valor,
                                    @RequestParam(required = false) String jugador, Authentication auth) {
        return informes.periodo(usuario(auth), categoria, rama, periodo, valor, jugador);
    }

    @GetMapping("/informes.pdf")
    public ResponseEntity<byte[]> informePeriodoPdf(@RequestParam(required = false) String categoria,
                                                    @RequestParam(required = false) String rama,
                                                    @RequestParam Periodo periodo,
                                                    @RequestParam(required = false) String valor,
                                                    @RequestParam(required = false) String jugador,
                                                    Authentication auth) {
        return pdf(informes.pdf(informes.periodo(usuario(auth), categoria, rama, periodo, valor, jugador)));
    }

    /** El PDF se abre en el navegador (inline); desde ahí se guarda o se comparte. */
    private static ResponseEntity<byte[]> pdf(DtInformeService.Pdf p) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(p.archivo()).build()
                        .toString())
                .body(p.contenido());
    }

    private Usuario usuario(Authentication auth) {
        return usuarios.findByUsuario(auth.getName()).filter(Usuario::esDt)
                .orElseThrow(() -> new AccessDeniedException("Tu usuario no es DT."));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ProblemDetail badRequest(Exception e) {
        String detail = e instanceof IllegalArgumentException ? e.getMessage() : "El pedido no es válido.";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(DtService.NoEncontrado.class)
    ProblemDetail notFound(DtService.NoEncontrado e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({DtService.Conflicto.class, IllegalStateException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SidgadException.class)
    ProblemDetail sidgad(SidgadException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "No se pudo leer SIDGAD: " + e.getMessage());
    }
}
