package com.madsports.report;

import com.madsports.player.PlayerReport;
import com.madsports.player.SamplePlayerData;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Los informes en Excel: las mismas cifras que el PDF, en hojas para trabajar los números. */
@SpringBootTest(properties = "rinkmetrics.ai.enabled=false")
@AutoConfigureMockMvc
class ExcelExporterTest {

    static final LocalDate HOY = LocalDate.of(2026, 10, 13);

    @Autowired ExcelExporter excel;
    @Autowired SampleReport sample;
    @Autowired MockMvc mvc;

    @Test
    void elDeClubTieneElResumenYUnaHojaPorCategoria() throws IOException {
        ClubReport r = sample.clubReport(HOY);
        Map<String, List<Row>> hojas = read(excel.club(r));
        assertThat(hojas.keySet()).containsExactly("Resumen", "Primera Masculino", "Juvenil Masculino", "Infantil Femenino");

        // En el resumen, una fila por categoría con sus partidos y goles.
        ClubReport.CategoryBlock primera = r.branches().getFirst().categories().getFirst();
        Row fila = hojas.get("Resumen").stream().filter(x -> "Primera".equals(x.getCellText(1))).findFirst().orElseThrow();
        assertThat(fila.getCellText(0)).isEqualTo("Masculino");
        assertThat(number(fila, 2)).isEqualTo(primera.record().played());
        assertThat(number(fila, 7)).isEqualTo(primera.record().goalsFor());
        assertThat(number(fila, 13)).isEqualTo(primera.yellowCards());

        // La hoja de la categoría trae sus apartados y el top 3 de goleo, con sus goles.
        List<Row> hoja = hojas.get("Primera Masculino");
        List<String> textos = hoja.stream().map(x -> x.getCellText(0)).toList();
        assertThat(textos).contains("Partidos", "Goles", "Tarjetas", "Top 3 · Promedio de gol", "Top 3 · Libres directos",
                "Top 3 · Penales", "Top 3 · Disciplina", "Plantel", "Fortalezas y debilidades", "Análisis");
        ClubReport.Ranked goleador = primera.topScorers().getFirst();
        Row suyo = hoja.stream().filter(x -> goleador.player().name().equals(x.getCellText(0))).findFirst().orElseThrow();
        assertThat(number(suyo, 1)).isEqualTo(goleador.count());
    }

    @Test
    void elDeJugadorTieneSusCategoriasPartidosYMinutos() throws IOException {
        PlayerReport r = SamplePlayerData.build(HOY, null);
        Map<String, List<Row>> hojas = read(excel.player(r));
        assertThat(hojas.keySet()).containsExactly("Resumen", "Partidos", "Minutos", "Fortalezas");
        // Un partido por fila, debajo del encabezado.
        assertThat(hojas.get("Partidos")).hasSize(r.matches().size() + 1);
        Row total = hojas.get("Resumen").stream().filter(x -> "Total".equals(x.getCellText(0))).findFirst().orElseThrow();
        assertThat(number(total, 1)).isEqualTo(r.total().played());
        assertThat(number(total, 5)).isEqualTo(r.total().goals());
    }

    @Test
    void losEjemplosSeVenEnPantallaYSeExportan() throws Exception {
        mvc.perform(get("/api/ejemplo.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Primera Masculino")));
        mvc.perform(get("/api/ejemplo-jugador.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/api/ejemplo.xlsx")).andExpect(status().isOk())
                .andExpect(content().contentType(Descargas.XLSX))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")));
        mvc.perform(get("/api/ejemplo-jugador.xlsx")).andExpect(status().isOk())
                .andExpect(content().contentType(Descargas.XLSX));
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
