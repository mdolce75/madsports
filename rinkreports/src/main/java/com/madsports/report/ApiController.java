package com.madsports.report;

import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.RosterService;
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
     * @param categories informe del club con solo esas categorías, de una o de las dos ramas ("JUVENIL|MASCULINO",
     *                   "INFANTIL:B|FEMENINO"); vacío, todas
     * @param federation FPP o AMP (ver {@link ReportFederations}); si falta, la del perfil del usuario
     */
    public record ReportRequest(@Positive int clubId, String category, String branch, String period, Integer year,
                                java.util.List<String> categories, String federation) {

        public ReportRequest(int clubId, String category, String branch, String period, Integer year) {
            this(clubId, category, branch, period, year, null, null);
        }

        boolean wholeClub() {
            return (category == null || category.isBlank()) && (branch == null || branch.isBlank());
        }
    }

    private final CatalogService catalog;
    private final ReportService reports;
    private final SampleReport sample;
    private final UserAccountRepository users;
    private final ReportQuota quota;
    private final RosterService roster;
    private final ReportFederations federations;

    public ApiController(CatalogService catalog, ReportService reports, SampleReport sample, UserAccountRepository users,
                         ReportQuota quota, RosterService roster, ReportFederations federations) {
        this.roster = roster;
        this.federations = federations;
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
    @GetMapping("/catalog")
    public CatalogView catalog(@RequestParam(required = false) Integer year,
                                @RequestParam(required = false) String federation) {
        return catalog.view(Federation.parse(federation), catalog.checkYear(year));
    }

    /**
     * Un club con solo las categorías que tienen jugadores en SIDGAD (las que se ofrecen en el registro del jugador y en
     * el informe del club).
     */
    @GetMapping("/catalog/club")
    public CatalogService.ClubView club(@RequestParam int clubId, @RequestParam(required = false) Integer year,
                                        @RequestParam(required = false) String federation) {
        Federation fed = Federation.parse(federation);
        int y = catalog.checkYear(year);
        return roster.withPlayers(fed, catalog.club(fed, clubId, y), y);
    }

    /** Federaciones del generador de informes. */
    @GetMapping("/federations")
    public java.util.List<ReportFederations.Option> federations() {
        return federations.options();
    }

    /** Jugadores de una categoría del club (los de sus planillas), para elegir el del informe de jugador. */
    @GetMapping("/catalog/players")
    public java.util.List<RosterService.RosterPlayer> players(@RequestParam int clubId, @RequestParam String category,
                                                             @RequestParam String branch,
                                                             @RequestParam(required = false) Integer year,
                                                             @RequestParam(required = false) String federation) {
        return roster.players(Federation.parse(federation), clubId, catalog.checkYear(year), category, branch);
    }

    /** Informe de una categoría o del club completo, de cualquier club de las federaciones ofrecidas. */
    @PostMapping("/reports")
    public ResponseEntity<JobView> create(@Valid @RequestBody ReportRequest req, Authentication auth) {
        UserAccount u = users.findByPrincipal(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Sesión inválida."));
        Federation fed = federations.resolve(req.federation(), u);
        ReportService.Scope scope = ReportService.Scope.parse(req.period());
        int year = catalog.checkYear(req.year());
        if (!req.wholeClub() && (req.category() == null || req.category().isBlank()
                || req.branch() == null || req.branch().isBlank())) {
            throw new IllegalArgumentException("Elegí la categoría y la rama, o ninguna para el informe del club completo.");
        }
        String club = catalog.club(fed, req.clubId(), year).name();
        ReportService.Job job = quota.consume(u, club + " · " + detail(req, scope, year), () -> req.wholeClub()
                ? reports.submitClub(req.clubId(), scope, year, fed, u.principal(), req.categories())
                : reports.submit(new ReportService.Request(req.clubId(), req.category(), req.branch(), scope, year,
                        fed), u.principal()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobView.of(job));
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<JobView> status(@PathVariable UUID id, Authentication auth) {
        return reports.find(id, auth.getName()).map(JobView::of).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Informe modelo de club con datos ficticios: en pantalla, en PDF (se abre en el navegador) y en Excel. */
    @GetMapping("/sample.html")
    public ResponseEntity<String> sampleHtml() {
        return Downloads.html(sample.club().html());
    }

    @GetMapping("/sample.pdf")
    public ResponseEntity<byte[]> sample() {
        return Downloads.file(sample.club().pdf(), MediaType.APPLICATION_PDF, "informe-club-ejemplo-rinkreports.pdf", true);
    }

    @GetMapping("/sample.xlsx")
    public ResponseEntity<byte[]> sampleXlsx() {
        return Downloads.file(sample.club().xlsx(), Downloads.XLSX, "informe-club-ejemplo-rinkreports.xlsx", false);
    }

    /** Informe modelo de jugador con datos ficticios: en pantalla, en PDF y en Excel. */
    @GetMapping("/sample-player.html")
    public ResponseEntity<String> samplePlayerHtml() {
        return Downloads.html(sample.player().html());
    }

    @GetMapping("/sample-player.pdf")
    public ResponseEntity<byte[]> samplePlayer() {
        return Downloads.file(sample.player().pdf(), MediaType.APPLICATION_PDF, "informe-jugador-ejemplo-rinkreports.pdf", true);
    }

    @GetMapping("/sample-player.xlsx")
    public ResponseEntity<byte[]> samplePlayerXlsx() {
        return Downloads.file(sample.player().xlsx(), Downloads.XLSX, "informe-jugador-ejemplo-rinkreports.xlsx", false);
    }

    @GetMapping("/reports/{id}/html")
    public ResponseEntity<String> viewHtml(@PathVariable UUID id, Authentication auth) {
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

    /** Qué informe es, para el historial: "Club completo · Apertura + Clausura 2026", "Juvenil Masculino · ...". */
    static String detail(ReportRequest req, ReportService.Scope scope, int year) {
        String what;
        if (!req.wholeClub()) {
            what = label(req.category()) + " " + label(req.branch());
        } else if (req.categories() == null || req.categories().isEmpty()) {
            what = "Club completo";
        } else {
            java.util.List<String> names = req.categories().stream().filter(java.util.Objects::nonNull).map(k -> {
                String[] p = k.split("\\|");
                return label(p[0]) + (p.length > 1 ? " " + label(p[1]) : "");
            }).toList();
            what = names.size() <= 3 ? String.join(", ", names) : names.size() + " categorías: "
                    + String.join(", ", names.subList(0, 3)) + "…";
        }
        String text = what + " · " + scope.label() + " " + year;
        return text.length() <= 200 ? text : text.substring(0, 199) + "…";
    }

    /** "INFANTIL:B" → "Infantil B", "MASCULINO" → "Masculino". */
    private static String label(String key) {
        String k = key == null ? "" : key.trim().replace(':', ' ').toLowerCase(java.util.Locale.ROOT);
        return java.util.Arrays.stream(k.split(" ")).filter(w -> !w.isEmpty())
                .map(w -> w.length() == 1 ? w.toUpperCase(java.util.Locale.ROOT)
                        : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }

}
