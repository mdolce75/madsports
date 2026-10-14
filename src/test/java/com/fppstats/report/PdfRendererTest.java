package com.fppstats.report;

import com.fppstats.analysis.AnalysisResult;
import com.fppstats.analysis.RuleBasedAnalyst;
import com.fppstats.player.PlayerReport;
import com.fppstats.stats.ReportData;
import com.fppstats.stats.StatsEngine;
import com.fppstats.stats.StatsEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "fpp.ai.enabled=false")
class PdfRendererTest {

    @Autowired
    PdfRenderer renderer;

    @Autowired
    RuleBasedAnalyst rules;

    @Test
    void generaElPdfConLasSeccionesDelInforme() throws Exception {
        ReportData data = StatsEngine.build(StatsEngineTest.VELEZ, "Infantil Femenino", "Clausura",
                List.of(StatsEngineTest.data(765)), StatsEngineTest.HOY);
        AnalysisResult analysis = rules.analyze(data);

        String html = renderer.html(data, analysis);
        assertThat(html).contains("VELEZ (CAVS)", "VELEZ A (CAVS A)", "VELEZ B (CAVS B)",
                "Goleadores y asistidores", "Pelota parada", "Cara a cara", "Análisis: Reglas");

        assertThat(html).doesNotContain("no tenía publicados");
        assertThat(renderer.html(data.withNotPublished(List.of("Actas de partido: 9 de 9")), analysis))
                .contains("no tenía publicados", "Actas de partido: 9 de 9");

        byte[] pdf = renderer.pdf(data, analysis);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // Copia para revisar a ojo: target/informe-ejemplo.pdf
        Files.write(Path.of("target", "informe-ejemplo.pdf"), pdf);
    }

    @Test
    void generaElPdfDelJugador() throws Exception {
        PlayerReport.Line primera = new PlayerReport.Line("Primera Masculino", 2, 1, 0, 1, 4, 1, 1, 0, 0);
        PlayerReport.Line juniors = new PlayerReport.Line("Juniors Masculino", 1, 1, 0, 0, 3, 0, 0, 0, 1);
        PlayerReport report = new PlayerReport(3851, "Ignacio J Graiño", "Ignacio Graiño",
                new ReportData.ClubInfo(74, "CAVS", "VELEZ", Crests.sample("CV")), 2026, "Apertura + Clausura",
                StatsEngineTest.HOY, List.of(juniors, primera),
                new PlayerReport.Line("Total", 3, 2, 0, 1, 7, 1, 1, 0, 1),
                List.of(new PlayerReport.MatchRow(StatsEngineTest.HOY, "Primera Masculino", "Clausura",
                        "ARGENTINOS JUNIORS", true, 2, 4, "P", true, 2, 1, 1, 0)),
                List.of("Juniors Masculino: 1 partido del equipo no tiene acta publicada; si jugó, no está contado."));

        String html = renderer.playerHtml(report);
        assertThat(html).contains("Ignacio J Graiño", "VELEZ · Temporada 2026 · Apertura + Clausura",
                "Partidos jugados", "Goles", "Tarjetas recibidas", "Partido a partido", "Juniors Masculino",
                "no tiene acta publicada", "Escudo de VELEZ");
        // Dona de resultados, dona de tarjetas y el escudo.
        assertThat(html.split("src=\"data:image/png;base64,", -1)).hasSize(3 + 1);

        byte[] pdf = renderer.playerPdf(report);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        Files.write(Path.of("target", "informe-jugador.pdf"), pdf);
    }
}
