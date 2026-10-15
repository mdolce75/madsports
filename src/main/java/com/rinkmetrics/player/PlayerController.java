package com.rinkmetrics.player;

import com.rinkmetrics.account.Perfil;
import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.account.UsuarioRepository;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.report.ApiController.JobView;
import com.rinkmetrics.report.ReportService.Scope;
import com.rinkmetrics.report.ReportService.Status;
import com.rinkmetrics.sidgad.SidgadClient.SidgadException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Informe del jugador logueado (perfil PLAYERFPP): solo elige año y período; el jugador y el club son los suyos. */
@RestController
@RequestMapping("/api/jugador")
public class PlayerController {

    /**
     * @param anio    año (p. ej. 2026); si falta, el más reciente con datos
     * @param periodo APERTURA, CLAUSURA o TEMPORADA (Apertura + Clausura, por defecto)
     */
    public record PlayerReportRequest(Integer anio, String periodo) {
    }

    private final PlayerReportService reports;
    private final UsuarioRepository usuarios;
    private final CatalogService catalog;

    public PlayerController(PlayerReportService reports, UsuarioRepository usuarios, CatalogService catalog) {
        this.reports = reports;
        this.usuarios = usuarios;
        this.catalog = catalog;
    }

    @PostMapping("/informes")
    public ResponseEntity<JobView> crear(@RequestBody PlayerReportRequest req, Authentication auth) {
        Usuario u = usuarios.findByUsuario(auth.getName())
                .filter(x -> x.tiene(Perfil.PLAYERFPP) && x.getJugadorId() != null)
                .orElseThrow(() -> new AccessDeniedException("Tu usuario no tiene un jugador vinculado."));
        PlayerReportService.Job job = reports.submit(new PlayerReportService.Query(u.getJugadorId(), u.getJugadorNombre(),
                u.getNombre() == null ? u.getJugadorNombre() : u.getNombre() + " " + u.getApellido(), u.getClubId(), catalog.checkYear(req.anio()),
                Scope.parse(req.periodo()), Eligibility.levels(u.getCategoria()), u.getRama()), u.getUsuario());
        usuarios.contarInforme(u.getUsuario());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(job));
    }

    @GetMapping("/informes/{id}")
    public ResponseEntity<JobView> estado(@PathVariable UUID id, Authentication auth) {
        return reports.find(id, auth.getName()).map(PlayerController::view).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/informes/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, Authentication auth) {
        return reports.find(id, auth.getName())
                .filter(j -> j.status() == Status.LISTO)
                .map(j -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(j.fileName()).build().toString())
                        .body(j.pdf()))
                .orElse(ResponseEntity.notFound().build());
    }

    private static JobView view(PlayerReportService.Job job) {
        return new JobView(job.id(), job.status(), job.message(),
                job.status() == Status.LISTO ? "/api/jugador/informes/" + job.id() + "/pdf" : null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(SidgadException.class)
    ProblemDetail upstream(SidgadException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "No se pudo leer el sitio de la FPP: " + e.getMessage());
    }
}
