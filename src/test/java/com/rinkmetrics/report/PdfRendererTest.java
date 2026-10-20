package com.rinkmetrics.report;

import com.rinkmetrics.analysis.AnalysisResult;
import com.rinkmetrics.analysis.RuleBasedAnalyst;
import com.rinkmetrics.player.PlayerReport;
import com.rinkmetrics.stats.ReportData;
import com.rinkmetrics.stats.StatsEngine;
import com.rinkmetrics.stats.StatsEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "rinkmetrics.ai.enabled=false")
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
        // Con actas (Primera Masculino, Clausura): sección de goles con uno menos.
        ReportData conActas = StatsEngine.build(StatsEngineTest.VELEZ, "Primera Masculino", "Clausura",
                List.of(StatsEngineTest.data(779)), StatsEngineTest.HOY);
        assertThat(renderer.html(conActas, rules.analyze(conActas))).contains("Goles con uno menos");
        assertThat(html).contains("VELEZ (CAVS)", "VELEZ A (CAVS A)", "VELEZ B (CAVS B)",
                "Goleadores", "Pelota parada", "Cara a cara", "Análisis: Reglas");

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
        PlayerReport.Line primera = new PlayerReport.Line("Primera Masculino", 2, 1, 0, 1, 4, 1, 1, 0, 1, 2, 1, 1, 0, 10, 8, 2);
        PlayerReport.Line juniors = new PlayerReport.Line("Juniors Masculino", 1, 1, 0, 0, 3, 0, 0, 0, 0, 0, 0, 1, 1, 5, 0, 0);
        List<PlayerReport.Bucket> buckets = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            buckets.add(new PlayerReport.Bucket((i < 5 ? "1T " : "2T ") + (i % 5) * 5 + "-" + ((i % 5) * 5 + 5) + "'",
                    i % 3, i == 4 ? 1 : 0, i == 7 ? 1 : 0, 0));
        }
        PlayerReport.Minutes minutes = new PlayerReport.Minutes(25, buckets,
                new PlayerReport.Bucket("Sin minuto registrado", 1, 0, 0, 0));
        ReportData.ShortHanded sh = new ReportData.ShortHanded(List.of(new ReportData.ShortHandedMatch(StatsEngineTest.HOY,
                "ARGENTINOS JUNIORS", true, "1 azul", 120, 1, 0, 2, 4)), 2880, 3,
                List.of(new ReportData.ConcededGoal(StatsEngineTest.HOY, "ARGENTINOS JUNIORS", "Azul", "1T 12:30",
                        "1T 13:40", 2, 4)));
        PlayerReport.MatchRow partido = new PlayerReport.MatchRow(StatsEngineTest.HOY, "Primera Masculino", "Clausura",
                "ARGENTINOS JUNIORS", true, 2, 4, "P", true, 2, 1, 1, 0);
        PlayerReport.MatchRow sinActa = new PlayerReport.MatchRow(StatsEngineTest.HOY, "Primera Masculino", "Clausura",
                "COMUNICACIONES", false, 3, 1, "G", false, 1, 0, 0, 0, true);
        List<PlayerReport.CategoryBlock> blocks = List.of(
                new PlayerReport.CategoryBlock(juniors, new PlayerReport.Minutes(25, buckets,
                        new PlayerReport.Bucket("Sin minuto registrado", 0, 0, 0, 0)), ReportData.ShortHanded.EMPTY, List.of()),
                new PlayerReport.CategoryBlock(primera, minutes, sh, List.of(partido, sinActa)));
        PlayerReport report = new PlayerReport(3851, "Ignacio J Graiño", "Ignacio Graiño",
                new ReportData.ClubInfo(74, "CAVS", "VELEZ", Crests.sample("CV")), 2026, "Apertura + Clausura",
                StatsEngineTest.HOY, List.of(juniors, primera),
                new PlayerReport.Line("Total", 3, 2, 0, 1, 7, 1, 1, 0, 1, 2, 1, 2, 1, 15, 8, 2), minutes, sh, List.of(partido),
                List.of("Juniors Masculino: 1 partido del equipo no tiene acta publicada; si jugó, no está contado."),
                blocks);

        String html = renderer.playerHtml(report);
        assertThat(html).contains("Ignacio J Graiño", "VELEZ · Temporada 2026 · Apertura + Clausura",
                "Resumen por categoría", "Partido a partido", "Juniors Masculino", "Primera Masculino",
                "no tiene acta publicada", "Escudo de VELEZ", "Penales y libres directos",
                "Goles y tarjetas por tramos de 5 minutos", "Sin minuto registrado", "2T 20-25",
                "Goles con uno menos por sus sanciones",
                "Goles que le hicieron a su equipo mientras cumplía su sanción: 1.", "1T 12:30", "1T 13:40",
                "No cumplió sanciones (azul o roja) con minuto registrado",
                "Todas las categorías", "COMUNICACIONES *", "Partido sin acta publicada",
                // Incidencia de sus goles: 4 de 10 en Primera, 3 de 5 en Juniors, 7 de 15 en total.
                "Incid.", "De los goles del equipo (4 de 10)", "De los goles del equipo (3 de 5)", ">40%<", ">60%<", ">47%<",
                // Tarjetas: 2 de las 8 del equipo en Primera (25%); en Juniors no hay tarjetas del equipo ("-").
                "De las tarjetas del equipo (2 de 8)", ">25%<", "De las tarjetas del equipo (0 de 0)");
        // Agrupado por categoría: un bloque por cada una (Juniors antes que Primera) y después el total.
        assertThat(html.indexOf(">Juniors Masculino</h2>")).isPositive()
                .isLessThan(html.indexOf(">Primera Masculino</h2>"));
        assertThat(html.indexOf(">Primera Masculino</h2>")).isLessThan(html.indexOf(">Todas las categorías</h2>"));

        byte[] pdf = renderer.playerPdf(report);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        Files.write(Path.of("target", "informe-jugador.pdf"), pdf);
    }
}
