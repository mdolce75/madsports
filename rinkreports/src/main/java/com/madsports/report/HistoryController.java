package com.madsports.report;

import com.madsports.account.UserAccountRepository;
import com.madsports.account.UserAccount;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Historial de informes generados del usuario (club o jugador): cuándo, cuál y, mientras se conserven, sus archivos
 * para volver a verlo o bajarlo tal como salió ({@link ReportArchive}). Cada usuario ve y baja solo los suyos.
 */
@RestController
@RequestMapping("/api")
public class HistoryController {

    /**
     * @param createdAt fecha y hora en que se pidió (UTC; la pantalla la muestra en la hora argentina)
     * @param available sus archivos siguen guardados: se puede ver y bajar
     */
    public record HistoryItem(Long id, Instant createdAt, String detail, boolean available) {
    }

    private final RequestedReportRepository requests;
    private final UserAccountRepository users;
    private final ReportArchive archive;

    public HistoryController(RequestedReportRepository requests, UserAccountRepository users, ReportArchive archive) {
        this.requests = requests;
        this.users = users;
        this.archive = archive;
    }

    @GetMapping("/history")
    public List<HistoryItem> history(Authentication auth) {
        return requests.findTop50ByUserIdOrderByCreatedAtDesc(me(auth).getId()).stream()
                .map(r -> new HistoryItem(r.getId(), r.getCreatedAt(), r.getDetail() == null ? "" : r.getDetail(),
                        r.isArchived())).toList();
    }

    @GetMapping("/history/{id}.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id, Authentication auth) {
        return mine(id, auth).flatMap(r -> archive.read(r, "pdf")
                        .map(b -> Downloads.file(b, MediaType.APPLICATION_PDF, name(r, "pdf"), false)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/history/{id}.xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable Long id, Authentication auth) {
        return mine(id, auth).flatMap(r -> archive.read(r, "xlsx")
                        .map(b -> Downloads.file(b, Downloads.XLSX, name(r, "xlsx"), false)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** El informe para verlo en pantalla (el mismo HTML que el PDF). */
    @GetMapping("/history/{id}/html")
    public ResponseEntity<String> html(@PathVariable Long id, Authentication auth) {
        return mine(id, auth).flatMap(r -> archive.read(r, "html"))
                .map(b -> Downloads.html(new String(b, StandardCharsets.UTF_8)))
                .orElse(ResponseEntity.notFound().build());
    }

    private UserAccount me(Authentication auth) {
        return users.findByPrincipal(auth.getName()).orElseThrow(() -> new AccessDeniedException("Sesión inválida."));
    }

    /** El pedido, si es del usuario (el de otro da 404, igual que uno que no existe). */
    private Optional<RequestedReport> mine(Long id, Authentication auth) {
        Long userId = me(auth).getId();
        return requests.findById(id).filter(r -> r.getUserId().equals(userId));
    }

    private static String name(RequestedReport r, String extension) {
        String base = r.getFileName() == null || r.getFileName().isBlank() ? "informe-" + r.getId() + ".pdf" : r.getFileName();
        return base.substring(0, base.lastIndexOf('.') + 1) + extension;
    }
}
