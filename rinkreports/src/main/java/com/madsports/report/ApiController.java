package com.madsports.report;

import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.CatalogView;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.SidgadClient.SidgadException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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

import java.util.Optional;
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
    public record ReportRequest(@Positive int clubId, String category, String branch, String period, Integer year) {

        boolean wholeClub() {
            return (category == null || category.isBlank()) && (branch == null || branch.isBlank());
        }
    }

    private final CatalogService catalog;
    private final ReportService reports;
    private final SampleReport sample;
    private final UserAccountRepository users;
    private final ReportQuota quota;

    public ApiController(CatalogService catalog, ReportService reports, SampleReport sample, UserAccountRepository users,
                         ReportQuota quota) {
        this.catalog = catalog;
        this.reports = reports;
        this.sample = sample;
        this.users = users;
        this.quota = quota;
    }

    /**
     * Catálogo de la federación (FPP por defecto, o AMP) del año pedido (o del más reciente con datos). Si el año no
     * tiene datos, {@code available} es false.
     */
    @GetMapping("/catalogo")
    public CatalogView catalog(@RequestParam(required = false) Integer year,
                                @RequestParam(required = false) String federation) {
        return catalog.view(Federation.parse(federation), catalog.checkYear(year));
    }

    /** Un usuario de club (CLUBFPP o CLUBAMP) pide informes solo de su club, en su federación. */
    @PostMapping("/informes")
    public ResponseEntity<JobView> create(@Valid @RequestBody ReportRequest req, Authentication auth) {
        UserAccount u = users.findByUsername(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Sesión inválida."));
        if (u.getClubId() != req.clubId()) {
            throw new AccessDeniedException("Solo podés pedir informes de tu club (" + u.getClubName() + ").");
        }
        ReportService.Scope scope = ReportService.Scope.parse(req.period());
        int year = catalog.checkYear(req.year());
        if (!req.wholeClub() && (req.category() == null || req.category().isBlank()
                || req.branch() == null || req.branch().isBlank())) {
            throw new IllegalArgumentException("Elegí la categoría y la rama, o ninguna para el informe del club completo.");
        }
        ReportService.Job job = quota.consume(u, () -> req.wholeClub()
                ? reports.submitClub(req.clubId(), scope, year, u.federation(), u.getUsername())
                : reports.submit(new ReportService.Request(req.clubId(), req.category(), req.branch(), scope, year,
                        u.federation()), u.getUsername()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobView.of(job));
    }

    @GetMapping("/informes/{id}")
    public ResponseEntity<JobView> status(@PathVariable UUID id, Authentication auth) {
        return reports.find(id, auth.getName()).map(JobView::of).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Informe modelo de club con datos ficticios: en pantalla, en PDF (se abre en el navegador) y en Excel. */
    @GetMapping("/ejemplo.html")
    public ResponseEntity<String> sampleHtml() {
        return Downloads.html(sample.club().html());
    }

    @GetMapping("/ejemplo.pdf")
    public ResponseEntity<byte[]> sample() {
        return Downloads.file(sample.club().pdf(), MediaType.APPLICATION_PDF, "informe-club-ejemplo-rinkreports.pdf", true);
    }

    @GetMapping("/ejemplo.xlsx")
    public ResponseEntity<byte[]> sampleXlsx() {
        return Downloads.file(sample.club().xlsx(), Downloads.XLSX, "informe-club-ejemplo-rinkreports.xlsx", false);
    }

    /** Informe modelo de jugador con datos ficticios: en pantalla, en PDF y en Excel. */
    @GetMapping("/ejemplo-jugador.html")
    public ResponseEntity<String> samplePlayerHtml() {
        return Downloads.html(sample.player().html());
    }

    @GetMapping("/ejemplo-jugador.pdf")
    public ResponseEntity<byte[]> samplePlayer() {
        return Downloads.file(sample.player().pdf(), MediaType.APPLICATION_PDF, "informe-jugador-ejemplo-rinkreports.pdf", true);
    }

    @GetMapping("/ejemplo-jugador.xlsx")
    public ResponseEntity<byte[]> samplePlayerXlsx() {
        return Downloads.file(sample.player().xlsx(), Downloads.XLSX, "informe-jugador-ejemplo-rinkreports.xlsx", false);
    }

    @GetMapping("/informes/{id}/html")
    public ResponseEntity<String> viewHtml(@PathVariable UUID id, Authentication auth) {
        return ready(id, auth).map(j -> Downloads.html(j.html())).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/informes/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, Authentication auth) {
        return ready(id, auth).map(j -> Downloads.file(j.pdf(), MediaType.APPLICATION_PDF, j.fileName(), false))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/informes/{id}/xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable UUID id, Authentication auth) {
        return ready(id, auth).map(j -> Downloads.file(j.xlsx(), Downloads.XLSX, j.fileName("xlsx"), false))
                .orElse(ResponseEntity.notFound().build());
    }

    private Optional<ReportService.Job> ready(UUID id, Authentication auth) {
        return reports.find(id, auth.getName()).filter(j -> j.status() == ReportService.Status.READY);
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
