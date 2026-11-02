package com.madsports.report;

import java.util.UUID;

/**
 * Estado de un informe (de club o de jugador); listo, con sus tres formatos: para ver en pantalla (htmlUrl), PDF y
 * Excel.
 *
 * @param nextOpponent en el informe de una categoría, el rival de su próximo partido (para compararlo); si no, null
 */
public record JobView(UUID id, ReportService.Status status, String message, String pdfUrl, String htmlUrl,
                      String xlsxUrl, String nextOpponent) {

    /** @param base "/api/informes/{id}" o "/api/jugador/informes/{id}" */
    public static JobView of(UUID id, ReportService.Status status, String message, String base) {
        boolean ready = status == ReportService.Status.READY;
        return new JobView(id, status, message, ready ? base + "/pdf" : null, ready ? base + "/html" : null,
                ready ? base + "/xlsx" : null, null);
    }

    static JobView of(ReportService.Job job) {
        JobView v = of(job.id(), job.status(), job.message(), "/api/reports/" + job.id());
        return new JobView(v.id(), v.status(), v.message(), v.pdfUrl(), v.htmlUrl(), v.xlsxUrl(),
                job.status() == ReportService.Status.READY ? job.nextOpponent() : null);
    }
}
