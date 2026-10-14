package com.rinkmetrics.report;

import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.account.UsuarioRepository;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.CatalogView;
import com.rinkmetrics.sidgad.SidgadClient.SidgadException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
     * @param categoria INFANTIL, PREJUVENIL, JUVENIL, JUNIORS, RESERVA o PRIMERA
     * @param rama      MASCULINO o FEMENINO
     * @param periodo   APERTURA, CLAUSURA, TEMPORADA (Apertura + Clausura, por defecto) o ULTIMOS_5
     * @param anio      año (p. ej. 2026); si falta, el más reciente con datos
     */
    public record ReportRequest(@Positive int clubId, @NotBlank String categoria, @NotBlank String rama, String periodo,
                                Integer anio) {
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

    /** Catálogo del año pedido (o del más reciente con datos). Si el año no tiene datos, {@code available} es false. */
    @GetMapping("/catalogo")
    public CatalogView catalogo(@RequestParam(required = false) Integer anio) {
        return catalog.view(catalog.checkYear(anio));
    }

    /** Un usuario CLUBFPP pide informes solo de su club (el que eligió al registrarse). */
    @PostMapping("/informes")
    public ResponseEntity<JobView> crear(@Valid @RequestBody ReportRequest req, Authentication auth) {
        Usuario u = usuarios.findByUsuario(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Sesión inválida."));
        if (u.getClubId() != req.clubId()) {
            throw new AccessDeniedException("Solo podés pedir informes de tu club (" + u.getClubNombre() + ").");
        }
        ReportService.Job job = reports.submit(new ReportService.Request(req.clubId(), req.categoria(), req.rama(),
                ReportService.Scope.parse(req.periodo()), catalog.checkYear(req.anio())), u.getUsuario());
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
                        ContentDisposition.inline().filename("informe-ejemplo-mad-sports.pdf").build().toString())
                .body(sample.pdf());
    }

    /** Informe modelo de jugador con datos ficticios; se abre en el navegador (inline). */
    @GetMapping("/ejemplo-jugador.pdf")
    public ResponseEntity<byte[]> ejemploJugador() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("informe-jugador-ejemplo-mad-sports.pdf").build().toString())
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
