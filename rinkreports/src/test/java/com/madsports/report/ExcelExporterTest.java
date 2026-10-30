package com.madsports.report;

import com.madsports.player.PlayerReport;
import com.madsports.player.SamplePlayerData;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Los informes en Excel: las mismas cifras que el PDF, en hojas para trabajar los números. */
@SpringBootTest(classes = ReportsTestConfig.class)
class ExcelExporterTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Autowired ExcelExporter excel;
    @Autowired SampleReport sample;

    @Test
    void clubWorkbookHasSummaryAndOneSheetPerCategory() throws IOException {
        ClubReport r = sample.clubReport(TODAY);
        Map<String, List<Row>> sheets = read(excel.club(r));
        assertThat(sheets.keySet()).containsExactly("Resumen", "Primera Masculino", "Juvenil Masculino", "Infantil Femenino");

        // En el resumen, una fila por categoría con sus partidos y goles.
        ClubReport.CategoryBlock primera = r.branches().getFirst().categories().getFirst();
        Row row = sheets.get("Resumen").stream().filter(x -> "Primera".equals(x.getCellText(1))).findFirst().orElseThrow();
        assertThat(row.getCellText(0)).isEqualTo("Masculino");
        assertThat(number(row, 2)).isEqualTo(primera.record().played());
        assertThat(number(row, 7)).isEqualTo(primera.record().goalsFor());
        assertThat(number(row, 13)).isEqualTo(primera.yellowCards());

        // La hoja de la categoría trae sus apartados y el top 3 de goleo, con sus goles.
        List<Row> sheet = sheets.get("Primera Masculino");
        List<String> texts = sheet.stream().map(x -> x.getCellText(0)).toList();
        assertThat(texts).contains("Partidos", "Goles", "Tarjetas", "Top 3 · Promedio de gol", "Top 3 · Libres directos",
                "Top 3 · Penales", "Top 3 · Disciplina", "Plantel", "Fortalezas y debilidades", "Análisis");
        ClubReport.Ranked scorer = primera.topScorers().getFirst();
        Row own = sheet.stream().filter(x -> scorer.player().name().equals(x.getCellText(0))).findFirst().orElseThrow();
        assertThat(number(own, 1)).isEqualTo(scorer.count());
    }

    /** gestiondepsj no tiene siglas: si la sigla es el nombre, no se repite. */
    @Test
    void clubNameWithoutARepeatedCode() {
        assertThat(ExcelExporter.nameAndCode("VELEZ", "CAVS")).isEqualTo("VELEZ (CAVS)");
        assertThat(ExcelExporter.nameAndCode("Aberastain", "ABERASTAIN")).isEqualTo("Aberastain");
        assertThat(ExcelExporter.nameAndCode("Aberastain", "")).isEqualTo("Aberastain");
    }

    @Test
    void playerWorkbookHasCategoriesMatchesAndMinutes() throws IOException {
        PlayerReport r = SamplePlayerData.build(TODAY, null);
        Map<String, List<Row>> sheets = read(excel.player(r));
        assertThat(sheets.keySet()).containsExactly("Resumen", "Partidos", "Minutos", "Fortalezas");
        // Un partido por fila, debajo del encabezado.
        assertThat(sheets.get("Partidos")).hasSize(r.matches().size() + 1);
        Row total = sheets.get("Resumen").stream().filter(x -> "Total".equals(x.getCellText(0))).findFirst().orElseThrow();
        assertThat(number(total, 1)).isEqualTo(r.total().played());
        assertThat(number(total, 5)).isEqualTo(r.total().goals());
    }

    private static int number(Row row, int cell) {
        return row.getCellAsNumber(cell).map(BigDecimal::intValue).orElseThrow();
    }

    private static Map<String, List<Row>> read(byte[] xlsx) throws IOException {
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            return wb.getSheets().collect(Collectors.toMap(Sheet::getName, s -> {
                try {
                    return s.read();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, (a, b) -> a, java.util.LinkedHashMap::new));
        }
    }
}
