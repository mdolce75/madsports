package com.madsports.report;

import java.util.UUID;

/**
 * Estado de un informe (de club o de jugador); listo, con sus tres formatos: para ver en pantalla (htmlUrl), PDF y
 * Excel.
 */
public record JobView(UUID id, ReportService.Status status, String message, String pdfUrl, String htmlUrl,
                      String xlsxUrl) {

    /** @param base "/api/informes/{id}" o "/api/jugador/informes/{id}" */
    public static JobView of(UUID id, ReportService.Status status, String message, String base) {
        boolean ready = status == ReportService.Status.READY;
        return new JobView(id, status, message, ready ? base + "/pdf" : null, ready ? base + "/html" : null,
                ready ? base + "/xlsx" : null);
    }

    static JobView of(ReportService.Job job) {
        return of(job.id(), job.status(), job.message(), "/api/informes/" + job.id());
    }
}
