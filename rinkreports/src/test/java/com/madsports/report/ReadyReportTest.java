package com.madsports.report;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El aviso de informe listo, con el que se guarda en el historial: antes de que se vea como listo, y solo si salió. */
class ReadyReportTest {

    static ReportService.Job job() {
        return new ReportService.Job(new ReportService.Request(74, null, null, ReportService.Scope.SEASON, 2026), "REPORTS:club");
    }

    @Test
    void runsBeforeTheReportShowsAsReady() {
        ReportService.Job job = job();
        List<ReportService.Status> seen = new ArrayList<>();
        job.whenReady(() -> seen.add(job.status()));
        job.update(ReportService.Status.EXTRACTING, "Leyendo SIDGAD");
        assertThat(seen).isEmpty();
        job.update(ReportService.Status.READY, "Informe listo");
        // Corrió una vez, cuando el informe todavía no se veía como listo (la pantalla no lo pide antes de guardarlo).
        assertThat(seen).hasSize(1).doesNotContain(ReportService.Status.READY);
        assertThat(job.status()).isEqualTo(ReportService.Status.READY);
        // Ya listo: corre en el momento.
        job.whenReady(() -> seen.add(job.status()));
        assertThat(seen).hasSize(2).last().isEqualTo(ReportService.Status.READY);
    }

    @Test
    void aFailedReportIsNotSavedAndAFailingSaveDoesNotBreakTheReport() {
        ReportService.Job failed = job();
        List<String> runs = new ArrayList<>();
        failed.whenReady(() -> runs.add("guardado"));
        failed.update(ReportService.Status.ERROR, "No se pudo generar");
        assertThat(runs).isEmpty();

        ReportService.Job ok = job();
        ok.whenReady(() -> {
            throw new IllegalStateException("disco lleno");
        });
        ok.update(ReportService.Status.READY, "Informe listo");
        assertThat(ok.status()).isEqualTo(ReportService.Status.READY);
    }
}
