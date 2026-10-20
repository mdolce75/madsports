package com.rinkmetrics.report;

import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.account.UsuarioRepository;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.CatalogView;
import com.rinkmetrics.sidgad.model.Federacion;
import com.rinkmetrics.sidgad.SidgadClient.SidgadException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ApiController {

    /**
     * @param categoria INFANTIL, PREJUVENIL, JUVENIL, JUNIORS, RESERVA o PRIMERA ("INFANTIL:B" si el club tiene dos
     *                  equipos); sin categoría ni rama, el informe del club completo (todas sus ramas y categorías)
     * @param rama      MASCULINO o FEMENINO
     * @param periodo   APERTURA, CLAUSURA, TEMPORADA (Apertura + Clausura, por defecto) o ULTIMOS_5
     * @param anio      año (p. ej. 2026); si falta, el más reciente con datos
     */
    public record ReportRequest(@Positive int clubId, String categoria, String rama, String periodo, Integer anio) {

        boolean clubCompleto() {
            return (categoria == null || categoria.isBlank()) && (rama == null || rama.isBlank());
        }
    }

    public record JobView(UUID id, ReportService.Status estado, String mensaje, String pdfUrl) {

        static JobView of(ReportService.Job job) {
            return new JobView(job.id(), job.status(), job.message(),
                    job.status() == ReportService.Status.LISTO ? "/api/informes/" + job.id() + "/pdf" : null);
        }
    }

    private final CatalogService catalog;
    private final ReportService reports;
    private final SampleReport sample;
    private final UsuarioRepository usuarios;

    public ApiController(CatalogService catalog, ReportService reports, SampleReport sample, UsuarioRepository usuarios) {
        this.catalog = catalog;
        this.reports = reports;
        this.sample = sample;
        this.usuarios = usuarios;
    }

    /**
     * Catálogo de la federación (FPP por defecto, o AMP) del año pedido (o del más reciente con datos). Si el año no
     * tiene datos, {@code available} es false.
     */
    @GetMapping("/catalogo")
    public CatalogView catalogo(@RequestParam(required = false) Integer anio,
                                @RequestParam(required = false) String federacion) {
        return catalog.view(Federacion.parse(federacion), catalog.checkYear(anio));
    }

    /** Un usuario de club (CLUBFPP o CLUBAMP) pide informes solo de su club, en su federación. */
    @PostMapping("/informes")
    public ResponseEntity<JobView> crear(@Valid @RequestBody ReportRequest req, Authentication auth) {
        Usuario u = usuarios.findByUsuario(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Sesión inválida."));
        if (u.getClubId() != req.clubId()) {
            throw new AccessDeniedException("Solo podés pedir informes de tu club (" + u.getClubNombre() + ").");
        }
        ReportService.Scope scope = ReportService.Scope.parse(req.periodo());
        int year = catalog.checkYear(req.anio());
        if (!req.clubCompleto() && (req.categoria() == null || req.categoria().isBlank()
                || req.rama() == null || req.rama().isBlank())) {
            throw new IllegalArgumentException("Elegí la categoría y la rama, o ninguna para el informe del club completo.");
        }
        ReportService.Job job = req.clubCompleto()
                ? reports.submitClub(req.clubId(), scope, year, u.federacion(), u.getUsuario())
                : reports.submit(new ReportService.Request(req.clubId(), req.categoria(), req.rama(), scope, year,
                        u.federacion()), u.getUsuario());
        usuarios.contarInforme(u.getUsuario());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobView.of(job));
    }

    @GetMapping("/informes/{id}")
    public ResponseEntity<JobView> estado(@PathVariable UUID id, Authentication auth) {
        return reports.find(id, auth.getName()).map(JobView::of).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Informe modelo con datos ficticios; se abre en el navegador (inline). */
    @GetMapping("/ejemplo.pdf")
    public ResponseEntity<byte[]> ejemplo() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("informe-ejemplo-rinkmetrics.pdf").build().toString())
                .body(sample.pdf());
    }

    /** Informe modelo de jugador con datos ficticios; se abre en el navegador (inline). */
    @GetMapping("/ejemplo-jugador.pdf")
    public ResponseEntity<byte[]> ejemploJugador() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("informe-jugador-ejemplo-rinkmetrics.pdf").build().toString())
                .body(sample.playerPdf());
    }

    @GetMapping("/informes/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, Authentication auth) {
        return reports.find(id, auth.getName())
                .filter(j -> j.status() == ReportService.Status.LISTO)
                .map(j -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(j.fileName()).build().toString())
                        .body(j.pdf()))
                .orElse(ResponseEntity.notFound().build());
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
