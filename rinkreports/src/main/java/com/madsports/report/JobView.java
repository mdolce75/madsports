package com.madsports.report;

import java.util.UUID;

/**
 * Estado de un informe (de club o de jugador); listo, con sus tres formatos: para ver en pantalla (htmlUrl), PDF y
 * Excel.
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
