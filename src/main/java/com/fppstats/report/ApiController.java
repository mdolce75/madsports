package com.fppstats.report;

import com.fppstats.catalog.CatalogService;
import com.fppstats.catalog.CatalogService.CatalogView;
import com.fppstats.sidgad.SidgadClient.SidgadException;
import com.fppstats.stats.ReportData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ApiController {

    /**
     * @param categoria INFANTIL, PREJUVENIL, JUVENIL, JUNIORS, RESERVA o PRIMERA
     * @param rama      MASCULINO o FEMENINO
     * @param periodo   APERTURA, CLAUSURA, TEMPORADA (Apertura + Clausura, por defecto) o ULTIMOS_5
     */
    public record ReportRequest(@Positive int clubId, @NotBlank String categoria, @NotBlank String rama, String periodo) {
    }

    public record JobView(UUID id, ReportService.Status estado, String mensaje, String pdfUrl, String datosUrl) {

        static JobView of(ReportService.Job job) {
            boolean ready = job.status() == ReportService.Status.LISTO;
            return new JobView(job.id(), job.status(), job.message(),
                    ready ? "/api/informes/" + job.id() + "/pdf" : null,
                    ready ? "/api/informes/" + job.id() + "/datos.json" : null);
        }
    }

    private final CatalogService catalog;
    private final ReportService reports;
    private final SampleReport sample;

    public ApiController(CatalogService catalog, ReportService reports, SampleReport sample) {
        this.catalog = catalog;
        this.reports = reports;
        this.sample = sample;
    }

    @GetMapping("/catalogo")
    public CatalogView catalogo() {
        return catalog.view();
    }

    @PostMapping("/informes")
    public ResponseEntity<JobView> crear(@Valid @RequestBody ReportRequest req) {
        ReportService.Job job = reports.submit(new ReportService.Request(req.clubId(), req.categoria(), req.rama(),
                ReportService.Scope.parse(req.periodo())));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobView.of(job));
    }

    @GetMapping("/informes/{id}")
    public ResponseEntity<JobView> estado(@PathVariable UUID id) {
        return reports.find(id).map(JobView::of).map(ResponseEntity::ok)
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

    /** Todas las cifras del informe en JSON, para analizarlas con cualquier IA (ver docs/prompts). */
    @GetMapping("/informes/{id}/datos.json")
    public ResponseEntity<ReportData> datos(@PathVariable UUID id) {
        return reports.find(id)
                .filter(j -> j.status() == ReportService.Status.LISTO && j.data() != null)
                .map(j -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(j.fileName().replaceAll("\\.pdf$", ".json")).build().toString())
                        .body(j.data()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/informes/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        return reports.find(id)
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
