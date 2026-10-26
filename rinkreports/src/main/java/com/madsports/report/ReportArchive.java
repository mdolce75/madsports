package com.madsports.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Los informes generados, guardados para tenerlos a mano desde el historial: el PDF, el Excel y el HTML de cada pedido,
 * tal como salieron ese día (SIDGAD cambia: volver a generarlo daría otro informe). Van en una carpeta por usuario
 * ({@code <dir>/<usuario>/<pedido>.pdf|xlsx|html}); la base guarda solo el nombre del archivo y su tamaño. Se conservan
 * {@code madsports.reports.retention-months} meses: una tarea diaria borra los más viejos (el pedido queda en el
 * historial, sin el archivo).
 */
@Component
public class ReportArchive {

    private static final Logger log = LoggerFactory.getLogger(ReportArchive.class);
    private static final List<String> EXTENSIONS = List.of("pdf", "xlsx", "html");

    private final Path dir;
    private final int retentionMonths;
    private final RequestedReportRepository requests;
    private final Clock clock;

    public ReportArchive(@Value("${madsports.reports.dir:./data/informes}") String dir,
                         @Value("${madsports.reports.retention-months:12}") int retentionMonths,
                         RequestedReportRepository requests, Clock clock) {
        this.dir = Path.of(dir);
        this.retentionMonths = retentionMonths;
        this.requests = requests;
        this.clock = clock;
    }

    /** Guarda los archivos del informe del pedido. Si no se puede, el informe igual se entrega (queda en el log). */
    public void save(Long requestId, Long userId, ReadyReport report) {
        try {
            Files.createDirectories(dir.resolve(String.valueOf(userId)));
            Files.write(file(userId, requestId, "pdf"), report.pdf());
            Files.write(file(userId, requestId, "xlsx"), report.xlsx());
            Files.writeString(file(userId, requestId, "html"), report.html(), StandardCharsets.UTF_8);
            long size = report.pdf().length + report.xlsx().length;
            requests.findById(requestId).ifPresent(r -> {
                r.archived(report.fileName(), size);
                requests.save(r);
            });
        } catch (IOException | RuntimeException e) {
            log.warn("No se pudo guardar el informe {} del usuario {}: {}", requestId, userId, e.getMessage());
        }
    }

    /** Un archivo guardado del pedido ("pdf", "xlsx" o "html"), si sigue guardado. */
    public Optional<byte[]> read(RequestedReport r, String extension) {
        if (!r.isArchived() || !EXTENSIONS.contains(extension)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file(r.getUserId(), r.getId(), extension)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Borra los archivos de los pedidos de hace más de {@code retention-months} meses (todos los días, 4:30). */
    @Scheduled(cron = "${madsports.reports.cleanup-cron:0 30 4 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void cleanup() {
        Instant limit = clock.instant().atZone(ZoneId.of("America/Argentina/Buenos_Aires"))
                .minusMonths(retentionMonths).toInstant();
        for (RequestedReport r : requests.findByArchivedTrueAndCreatedAtBefore(limit)) {
            for (String ext : EXTENSIONS) {
                try {
                    Files.deleteIfExists(file(r.getUserId(), r.getId(), ext));
                } catch (IOException e) {
                    log.warn("No se pudo borrar el informe {}: {}", r.getId(), e.getMessage());
                }
            }
            r.expired();
            requests.save(r);
        }
    }

    private Path file(Long userId, Long requestId, String extension) {
        return dir.resolve(String.valueOf(userId)).resolve(requestId + "." + extension);
    }
}
