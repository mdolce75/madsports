package com.rinkmetrics.report;

import com.rinkmetrics.account.CupoInformes;
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

import java.nio.charset.StandardCharsets;
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
    public record ReportRequest(@Positive int clubId, String categoria, String rama, String periodo, Integer anio) {

        boolean clubCompleto() {
            return (categoria == null || categoria.isBlank()) && (rama == null || rama.isBlank());
        }
    }

    /**
     * Estado de un informe; listo, con sus tres formatos: para ver en pantalla (htmlUrl), PDF y Excel.
     */
    public record JobView(UUID id, ReportService.Status estado, String mensaje, String pdfUrl, String htmlUrl,
                          String xlsxUrl) {

        /** @param base "/api/informes/{id}" o "/api/jugador/informes/{id}" */
        public static JobView of(UUID id, ReportService.Status estado, String mensaje, String base) {
            boolean listo = estado == ReportService.Status.LISTO;
            return new JobView(id, estado, mensaje, listo ? base + "/pdf" : null, listo ? base + "/html" : null,
                    listo ? base + "/xlsx" : null);
        }

        static JobView of(ReportService.Job job) {
            return of(job.id(), job.status(), job.message(), "/api/informes/" + job.id());
        }
    }

    /** Tipo de los archivos de Excel (.xlsx). */
    public static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /** El informe para ver en pantalla (la página lo muestra dentro de un marco). */
    public static ResponseEntity<String> html(String html) {
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(html);
    }

    /** Un archivo para bajar (PDF o Excel); {@code inline} lo abre el navegador en lugar de descargarlo. */
    public static ResponseEntity<byte[]> file(byte[] body, MediaType type, String fileName, boolean inline) {
        ContentDisposition.Builder disposition = inline ? ContentDisposition.inline() : ContentDisposition.attachment();
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(fileName).build().toString())
                .body(body);
    }

    private final CatalogService catalog;
    private final ReportService reports;
    private final SampleReport sample;
    private final UsuarioRepository usuarios;
    private final CupoInformes cupo;

    public ApiController(CatalogService catalog, ReportService reports, SampleReport sample, UsuarioRepository usuarios,
                         CupoInformes cupo) {
        this.catalog = catalog;
        this.reports = reports;
        this.sample = sample;
        this.usuarios = usuarios;
        this.cupo = cupo;
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
        ReportService.Job job = cupo.pedir(u, () -> req.clubCompleto()
                ? reports.submitClub(req.clubId(), scope, year, u.federacion(), u.getUsuario())
                : reports.submit(new ReportService.Request(req.clubId(), req.categoria(), req.rama(), scope, year,
                        u.federacion()), u.getUsuario()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobView.of(job));
    }

    @GetMapping("/informes/{id}")
    public ResponseEntity<JobView> estado(@PathVariable UUID id, Authentication auth) {
        return reports.find(id, auth.getName()).map(JobView::of).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Informe modelo de club con datos ficticios: en pantalla, en PDF (se abre en el navegador) y en Excel. */
    @GetMapping("/ejemplo.html")
    public ResponseEntity<String> ejemploHtml() {
        return html(sample.club().html());
    }

    @GetMapping("/ejemplo.pdf")
    public ResponseEntity<byte[]> ejemplo() {
        return file(sample.club().pdf(), MediaType.APPLICATION_PDF, "informe-club-ejemplo-rinkreports.pdf", true);
    }

    @GetMapping("/ejemplo.xlsx")
    public ResponseEntity<byte[]> ejemploXlsx() {
        return file(sample.club().xlsx(), XLSX, "informe-club-ejemplo-rinkreports.xlsx", false);
    }

    /** Informe modelo de jugador con datos ficticios: en pantalla, en PDF y en Excel. */
    @GetMapping("/ejemplo-jugador.html")
    public ResponseEntity<String> ejemploJugadorHtml() {
        return html(sample.player().html());
    }

    @GetMapping("/ejemplo-jugador.pdf")
    public ResponseEntity<byte[]> ejemploJugador() {
        return file(sample.player().pdf(), MediaType.APPLICATION_PDF, "informe-jugador-ejemplo-rinkreports.pdf", true);
    }

    @GetMapping("/ejemplo-jugador.xlsx")
    public ResponseEntity<byte[]> ejemploJugadorXlsx() {
        return file(sample.player().xlsx(), XLSX, "informe-jugador-ejemplo-rinkreports.xlsx", false);
    }

    @GetMapping("/informes/{id}/html")
    public ResponseEntity<String> verHtml(@PathVariable UUID id, Authentication auth) {
        return ready(id, auth).map(j -> html(j.html())).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/informes/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, Authentication auth) {
        return ready(id, auth).map(j -> file(j.pdf(), MediaType.APPLICATION_PDF, j.fileName(), false))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/informes/{id}/xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable UUID id, Authentication auth) {
        return ready(id, auth).map(j -> file(j.xlsx(), XLSX, j.fileName("xlsx"), false))
                .orElse(ResponseEntity.notFound().build());
    }

    private Optional<ReportService.Job> ready(UUID id, Authentication auth) {
        return reports.find(id, auth.getName()).filter(j -> j.status() == ReportService.Status.LISTO);
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
