package com.madsports.player;

import com.madsports.account.Profile;
import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.RosterService;
import com.madsports.catalog.RosterService.RosterPlayer;
import com.madsports.sidgad.model.Federation;
import com.madsports.report.ReportFederations;
import com.madsports.report.ReportQuota;
import com.madsports.report.Downloads;
import com.madsports.report.JobView;
import com.madsports.report.ReportService.Scope;
import com.madsports.report.ReportService.Status;
import com.madsports.source.SourceException;
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

import java.util.Optional;
import java.util.UUID;

/**
 * Informe de jugador: el de cualquier jugador, elegido en el generador (federación, club, categoría y jugador). Sin
 * jugador, el del jugador vinculado al usuario (las cuentas de jugador de antes, PLAYERFPP o PLAYERAMP).
 */
@RestController
@RequestMapping("/api/player")
public class PlayerController {

    /**
     * @param anio    año (p. ej. 2026); si falta, el más reciente con datos
     * @param periodo APERTURA, CLAUSURA o TEMPORADA (Apertura + Clausura, por defecto)
     * @param playerId el jugador del informe, de la planilla de {@code category} y {@code branch} del club
     *                 ({@code /api/catalog/players}); null, el vinculado al usuario
     * @param category categoría del club en que figura ("INFANTIL" o "INFANTIL:B"): sirve para encontrarlo; el informe
     *                 incluye todas las categorías en que puede jugar
     */
    public record PlayerReportRequest(Integer year, String period, String federation, Integer clubId, String category,
                                      String branch, Integer playerId) {

        public PlayerReportRequest(Integer year, String period) {
            this(year, period, null, null, null, null, null);
        }
    }

    private final PlayerReportService reports;
    private final UserAccountRepository users;
    private final CatalogService catalog;
    private final ReportQuota quota;
    private final RosterService roster;
    private final ReportFederations federations;

    public PlayerController(PlayerReportService reports, UserAccountRepository users, CatalogService catalog,
                            ReportQuota quota, RosterService roster, ReportFederations federations) {
        this.roster = roster;
        this.federations = federations;
        this.reports = reports;
        this.users = users;
        this.catalog = catalog;
        this.quota = quota;
    }

    @PostMapping("/reports")
    public ResponseEntity<JobView> create(@RequestBody PlayerReportRequest req, Authentication auth) {
        UserAccount me = users.findByPrincipal(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Sesión inválida."));
        Scope scope = Scope.parse(req.period());
        int year = catalog.checkYear(req.year());
        if (req.playerId() != null) {
            return chosen(req, me, scope, year);
        }
        UserAccount u = Optional.of(me).filter(x -> x.isPlayer() && x.getPlayerId() != null)
                .orElseThrow(() -> new IllegalArgumentException("Elegí el jugador del informe."));
        Federation fed = u.federation();
        // Dentro del cupo del mes (5 informes en el Jugador free): cada pedido cuenta.
        PlayerReportService.Job job = quota.consume(u, "Jugador · " + scope.label() + " " + year,
                () -> reports.submit(new PlayerReportService.Query(u.getPlayerId(),
                u.getPlayerName(), u.getName() == null ? u.getPlayerName() : u.getName() + " " + u.getLastName(),
                u.getClubId(), year, scope,
                Eligibility.searchLevels(fed, u.getCategory()), u.getBranch(), fed), u.principal()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(job));
    }

    /** El jugador elegido en el generador: tiene que figurar en la planilla de la categoría del club. */
    private ResponseEntity<JobView> chosen(PlayerReportRequest req, UserAccount u, Scope scope, int year) {
        Federation fed = federations.resolve(req.federation(), u);
        if (req.clubId() == null || req.category() == null || req.category().isBlank()
                || req.branch() == null || req.branch().isBlank()) {
            throw new IllegalArgumentException("Elegí el club y la categoría del jugador.");
        }
        String branch = req.branch().trim().toUpperCase(java.util.Locale.ROOT);
        RosterPlayer p = roster.find(fed, req.clubId(), year, req.category(), branch, req.playerId())
                .orElseThrow(() -> new IllegalArgumentException("El jugador elegido no figura en la planilla de esa categoría."));
        String level = CatalogService.CategoryOption.parse(req.category()).level();
        PlayerReportService.Job job = quota.consume(u, "Jugador · " + p.name() + " · " + scope.label() + " " + year,
                () -> reports.submit(new PlayerReportService.Query(p.id(), p.name(), p.name(), req.clubId(), year,
                        scope, Eligibility.searchLevels(fed, level), branch, fed), u.principal()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(job));
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<JobView> status(@PathVariable UUID id, Authentication auth) {
        return reports.find(id, auth.getName()).map(PlayerController::view).map(ResponseEntity::ok)
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

    private Optional<PlayerReportService.Job> ready(UUID id, Authentication auth) {
        return reports.find(id, auth.getName()).filter(j -> j.status() == Status.READY);
    }

    private static JobView view(PlayerReportService.Job job) {
        return JobView.of(job.id(), job.status(), job.message(), "/api/player/reports/" + job.id());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(SourceException.class)
    ProblemDetail upstream(SourceException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "No se pudo leer SIDGAD: " + e.getMessage());
    }
}
