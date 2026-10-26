package com.madsports.report;

import com.madsports.account.UserAccountRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Historial de informes generados del usuario (club o jugador): cuándo y cuál. */
@RestController
@RequestMapping("/api")
public class HistoryController {

    /** @param createdAt fecha y hora en que se pidió (UTC; la pantalla la muestra en la hora argentina) */
    public record HistoryItem(Instant createdAt, String detail) {
    }

    private final RequestedReportRepository requests;
    private final UserAccountRepository users;

    public HistoryController(RequestedReportRepository requests, UserAccountRepository users) {
        this.requests = requests;
        this.users = users;
    }

    @GetMapping("/history")
    public List<HistoryItem> history(Authentication auth) {
        Long userId = users.findByPrincipal(auth.getName()).orElseThrow(() -> new AccessDeniedException("Sesión inválida."))
                .getId();
        return requests.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(r -> new HistoryItem(r.getCreatedAt(), r.getDetail() == null ? "" : r.getDetail())).toList();
    }
}
