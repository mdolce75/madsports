package com.madsports.report;

import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.compare.Comparison;
import com.madsports.compare.ComparisonCalc;
import com.madsports.sidgad.model.Federation;
import com.madsports.stats.StatsEngine.CompetitionData;
import com.madsports.stats.StatsEngineTest;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El comparativo en pantalla, en PDF y en Excel, con datos reales de SIDGAD (Primera Masculino, Clausura 2026). */
@SpringBootTest(classes = ReportsTestConfig.class)
class CompareRenderTest {

    @Autowired
    PdfRenderer renderer;

    @Autowired
    ExcelExporter excel;

    static Comparison velezVsArgentinos() {
        List<CompetitionData> data = List.of(StatsEngineTest.data(779));
        ClubView aaaj = new ClubView(94, "AAAJ", "ARGENTINOS JUNIORS", List.of());
        return ComparisonCalc.build(Federation.FPP, 2026, "Primera Masculino",
                new ComparisonCalc.Input(StatsEngineTest.VELEZ, "CAVS", data, null),
                new ComparisonCalc.Input(aaaj, "AAAJ", data, null), StatsEngineTest.TODAY, List.of());
    }

    @Test
    void htmlWithTheSectionsOfBothTeams() {
        String html = renderer.compareHtml(velezVsArgentinos());

        assertThat(html).contains("VELEZ vs ARGENTINOS JUNIORS", "Primera Masculino · FPP · Año 2026",
                "Top 3 · Goles", "Top 3 · Tarjetas", "Top 3 · Penales", "Top 3 · Libres directos",
                "Enfrentamientos del año", "Goles por tramo de 5 minutos");
        // Solo el período elegido: ni el torneo actual ni los últimos 5 aparte.
        assertThat(html).doesNotContain("Torneo actual", "Cómo llegan");
        // Vélez 2-4 Argentinos (acta 26390), con sus goleadores.
        assertThat(html).contains("2 - 4");
        // En cada métrica, resaltado el que va adelante (con la aclaración de qué es mejor).
        assertThat(html).contains("class=\"ahead\"", "Resaltado en naranja");
        Comparison c = velezVsArgentinos();
        int wonA = c.a().view().report().total().won();
        int wonB = c.b().view().report().total().won();
        String wonRow = html.substring(html.indexOf(">Ganados<"), html.indexOf(">Empatados<"));
        if (wonA == wonB) {
            assertThat(wonRow).doesNotContain("ahead");
        } else {
            // La celda resaltada es la del que ganó más (la primera es la de Vélez).
            assertThat(wonRow.indexOf("ahead") < wonRow.lastIndexOf("<td")).isEqualTo(wonA > wonB);
        }
        // Sin copias guardadas no se sabe la fecha de los datos; con ellas, en hora argentina.
        assertThat(html).doesNotContain("Datos de SIDGAD al");
        assertThat(renderer.compareHtml(velezVsArgentinos().withDataAsOf(java.time.Instant.parse("2026-11-02T09:00:00Z"))))
                .contains("Datos de SIDGAD al 02/11/2026 06:00");
    }

    @Test
    void theLastFiveMatchesWithoutThePlayerTables() {
        List<CompetitionData> data = List.of(StatsEngineTest.data(779));
        ClubView aaaj = new ClubView(94, "AAAJ", "ARGENTINOS JUNIORS", List.of());
        String html = renderer.compareHtml(ComparisonCalc.build(Federation.FPP, 2026, "Primera Masculino",
                ReportService.Scope.LAST_5, new ComparisonCalc.Input(StatsEngineTest.VELEZ, "CAVS", data, null),
                new ComparisonCalc.Input(aaaj, "AAAJ", data, null), StatsEngineTest.TODAY, List.of()));

        assertThat(html).contains("Primera Masculino · FPP · Últimos 5 partidos 2026", "Cómo llegan · últimos 5 partidos",
                "no se pueden recortar a los últimos 5 partidos");
        assertThat(html).doesNotContain("Top 3 · Goles");
    }

    @Test
    void pdfAndExcel() throws Exception {
        Comparison c = velezVsArgentinos();
        byte[] pdf = renderer.comparePdf(c);
        byte[] xlsx = excel.compare(c);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheets().map(s -> s.getName()).toList())
                    .containsExactly("Resumen", "Partidos", "Jugadores", "Tarjetas", "Minutos", "Enfrentamientos");
        }
        String out = System.getProperty("compare.out");
        if (out != null) {
            Files.write(Path.of(out + ".pdf"), pdf);
            Files.write(Path.of(out + ".xlsx"), xlsx);
        }
    }
}
