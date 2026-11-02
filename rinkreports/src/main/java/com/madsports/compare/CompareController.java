package com.madsports.compare;

import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
import com.madsports.catalog.CatalogService;
import com.madsports.report.Downloads;
import com.madsports.report.JobView;
import com.madsports.report.ReportFederations;
import com.madsports.report.ReportQuota;
import com.madsports.report.ReportService.Status;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.SidgadClient.SidgadException;
import com.madsports.sidgad.model.Federation;
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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** El comparativo de dos equipos desde el generador: se pide, se consulta y se baja como los otros informes. */
@RestController
@RequestMapping("/api/compare")
public class CompareController {

    /**
     * @param categoryA categoría del club A ("PRIMERA", "INFANTIL:B" si el club tiene dos equipos)
     * @param categoryB la del club B, del mismo nivel
     * @param branch    MASCULINO o FEMENINO
     * @param federation FPP o AMP; si falta, la del perfil del usuario
     */
    public record CompareRequest(Integer year, String federation, String branch, Integer clubA, String categoryA,
                                 Integer clubB, String categoryB) {
    }

    private final ComparisonService comparisons;
    private final UserAccountRepository users;
    private final CatalogService catalog;
    private final ReportQuota quota;
    private final ReportFederations federations;

    public CompareController(ComparisonService comparisons, UserAccountRepository users, CatalogService catalog,
                             ReportQuota quota, ReportFederations federations) {
        this.comparisons = comparisons;
        this.users = users;
        this.catalog = catalog;
        this.quota = quota;
        this.federations = federations;
    }

    @PostMapping("/reports")
    public ResponseEntity<JobView> create(@RequestBody CompareRequest req, Authentication auth) {
        UserAccount u = users.findByPrincipal(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Sesión inválida."));
        if (req.clubA() == null || req.clubB() == null || blank(req.categoryA()) || blank(req.categoryB())
                || blank(req.branch())) {
            throw new IllegalArgumentException("Elegí la categoría, la rama y los dos equipos.");
        }
        Federation fed = federations.resolve(req.federation(), u);
        int year = catalog.checkYear(req.year());
        ComparisonService.Request r = new ComparisonService.Request(fed, year,
                req.branch().trim().toUpperCase(Locale.ROOT), req.clubA(), req.categoryA(), req.clubB(), req.categoryB());
        List<ComparisonService.Team> teams = comparisons.validate(r);
        String detail = "Comparativo · " + teams.get(0).club().name() + " vs " + teams.get(1).club().name() + " · "
                + teams.get(0).category().label() + " " + year;
        ComparisonService.Job job = quota.consume(u, detail, () -> comparisons.submit(r, u.principal()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(job));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<JobView> status(@PathVariable UUID id, Authentication auth) {
        return comparisons.find(id, auth.getName()).map(CompareController::view).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/reports/{id}/html")
    public ResponseEntity<String> html(@PathVariable UUID id, Authentication auth) {
        return ready(id, auth).map(j -> Downloads.html(j.html())).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/reports/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, Authentication auth) {
        return ready(id, auth).map(j -> Downloads.file(j.pdf(), MediaType.APPLICATION_PDF, j.fileName(), false))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/reports/{id}/xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable UUID id, Authentication auth) {
        return ready(id, auth).map(j -> Downloads.file(j.xlsx(), Downloads.XLSX, j.fileName("xlsx"), false))
                .orElse(ResponseEntity.notFound().build());
    }

    private Optional<ComparisonService.Job> ready(UUID id, Authentication auth) {
        return comparisons.find(id, auth.getName()).filter(j -> j.status() == Status.READY);
    }

    private static JobView view(ComparisonService.Job job) {
        return JobView.of(job.id(), job.status(), job.message(), "/api/compare/reports/" + job.id());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(SidgadException.class)
    ProblemDetail upstream(SidgadException e) {
        if (e instanceof SidgadClient.NotSyncedException) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "No se pudo leer SIDGAD: " + e.getMessage());
    }
}
