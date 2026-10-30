package com.madsports.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué informe fue cada pedido, como se ve en el historial de informes generados. */
class HistoryDetailTest {

    @Test
    void clubReportDetail() {
        assertThat(ApiController.detail(new ApiController.ReportRequest(74, null, null, "SEASON", 2026),
                ReportService.Scope.SEASON, 2026)).isEqualTo("Club completo · Apertura + Clausura 2026");
        assertThat(ApiController.detail(new ApiController.ReportRequest(74, "INFANTIL:B", "FEMENINO", "OPENING", 2026),
                ReportService.Scope.OPENING, 2026)).isEqualTo("Infantil B Femenino · Apertura 2026");
        assertThat(ApiController.detail(new ApiController.ReportRequest(74, null, null, "CLOSING", 2026,
                        List.of("JUVENIL|MASCULINO", "PRIMERA|FEMENINO"), null), ReportService.Scope.CLOSING, 2026))
                .isEqualTo("Juvenil Masculino, Primera Femenino · Clausura 2026");
        assertThat(ApiController.detail(new ApiController.ReportRequest(74, null, null, "LAST_5", 2026,
                        List.of("INFANTIL:A|MASCULINO", "JUVENIL|MASCULINO", "RESERVA|MASCULINO", "PRIMERA|MASCULINO"), null),
                ReportService.Scope.LAST_5, 2026))
                .isEqualTo("4 categorías: Infantil A Masculino, Juvenil Masculino, Reserva Masculino… · Últimos 5 partidos 2026");
    }
}
