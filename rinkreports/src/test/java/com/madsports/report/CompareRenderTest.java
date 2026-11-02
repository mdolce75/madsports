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

        assertThat(html).contains("VELEZ vs ARGENTINOS JUNIORS", "Primera Masculino · FPP · Temporada 2026",
                "Cómo llegan · últimos 5 partidos", "Torneo actual · Clausura", "Año 2026", "Goleadores", "Disciplina",
                "Enfrentamientos del año", "Goles por tramo de 5 minutos");
        // Vélez 2-4 Argentinos (acta 26390), con sus goleadores.
        assertThat(html).contains("2 - 4");
    }

    @Test
    void pdfAndExcel() throws Exception {
        Comparison c = velezVsArgentinos();
        byte[] pdf = renderer.comparePdf(c);
        byte[] xlsx = excel.compare(c);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheets().map(s -> s.getName()).toList())
                    .containsExactly("Resumen", "Últimos 5", "Jugadores", "Tarjetas", "Minutos", "Enfrentamientos");
        }
        String out = System.getProperty("compare.out");
        if (out != null) {
            Files.write(Path.of(out + ".pdf"), pdf);
            Files.write(Path.of(out + ".xlsx"), xlsx);
        }
    }
}
