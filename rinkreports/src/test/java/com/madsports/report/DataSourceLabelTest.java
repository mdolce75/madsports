package com.madsports.report;

import com.madsports.analysis.RuleBasedAnalyst;
import com.madsports.pdf.Templates;
import com.madsports.player.PlayerReport;
import com.madsports.player.SamplePlayerData;
import com.madsports.sidgad.model.Federation;
import com.madsports.source.DataSource;
import com.madsports.stats.ReportData;
import com.madsports.stats.StatsEngine;
import com.madsports.stats.StatsEngineTest;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El nombre de la fuente en los informes sale de la federación: con otra fuente, ni el PDF ni el Excel dicen SIDGAD. */
@SpringBootTest(classes = ReportsTestConfig.class)
class DataSourceLabelTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Autowired Templates templates;
    @Autowired PdfRenderer sidgad;
    @Autowired ExcelExporter sidgadExcel;
    @Autowired SampleReport sample;
    @Autowired RuleBasedAnalyst rules;

    final ExcelExporter psjExcel = new ExcelExporter() {
        @Override
        protected DataSource sourceOf(Federation f) {
            return DataSource.PSJ;
        }
    };

    ReportData category() {
        return StatsEngine.build(StatsEngineTest.VELEZ, "Primera Masculino", "Clausura",
                List.of(StatsEngineTest.data(779)), StatsEngineTest.TODAY)
                .withNotPublished(List.of("Tabla de posiciones (Clausura)"));
    }

    @Test
    void fppReportsStillNameSidgad() throws IOException {
        ReportData d = category();
        assertThat(sidgad.html(d, rules.analyze(d), true)).contains("Datos: FPP (SIDGAD)", "en SIDGAD");
        ClubReport club = sample.clubReport(TODAY);
        assertThat(sidgad.clubHtml(club, true)).contains("Datos: FPP (SIDGAD)");
        assertThat(sidgad.playerHtml(SamplePlayerData.build(TODAY, null), true)).contains("Datos: FPP (SIDGAD)");
        assertThat(text(sidgadExcel.player(SamplePlayerData.build(TODAY, null)))).contains("Jugador en SIDGAD");
    }

    @Test
    void anotherSourceNeverShowsSidgad() throws IOException {
        PdfRenderer renderer = new PdfRenderer(templates) {
            @Override
            protected DataSource sourceOf(Federation f) {
                return DataSource.PSJ;
            }
        };
        ReportData d = category();
        String categoryHtml = renderer.html(d, rules.analyze(d), true) + renderer.html(d, rules.analyze(d), false);
        assertThat(categoryHtml).doesNotContain("SIDGAD").contains("Datos: FPP (gestiondepsj)", "en gestiondepsj");

        ClubReport club = sample.clubReport(TODAY);
        ClubReport missing = new ClubReport(club.club(), club.period(), club.generatedOn(), club.federation(),
                club.branches(), List.of("Actas de partido: 2 de 9"), club.sources());
        assertThat(renderer.clubHtml(missing, true)).doesNotContain("SIDGAD")
                .contains("Datos: FPP (gestiondepsj)", "gestiondepsj no publicó");
        assertThat(text(psjExcel.club(missing))).doesNotContain("SIDGAD").contains("Sin publicar en gestiondepsj");

        PlayerReport player = SamplePlayerData.build(TODAY, null);
        assertThat(renderer.playerHtml(player, true)).doesNotContain("SIDGAD").contains("Datos: FPP (gestiondepsj)");
        assertThat(text(psjExcel.player(player))).doesNotContain("SIDGAD").contains("Jugador en gestiondepsj");
    }

    @Test
    void competitionPageOfEachSource() {
        assertThat(DataSource.SIDGAD.competitionUrl(779)).isEqualTo("https://www.buenosaires.sidgad.com/league/779");
        assertThat(DataSource.PSJ.competitionUrl(200004)).isEqualTo("https://gestiondepsj.com.ar/");
    }

    /** El texto de todas las celdas del libro. */
    static String text(byte[] xlsx) throws IOException {
        StringBuilder out = new StringBuilder();
        try (ReadableWorkbook book = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            for (Sheet sheet : book.getSheets().toList()) {
                for (Row row : sheet.read()) {
                    row.forEach(c -> out.append(c == null ? "" : c.getRawValue()).append(' '));
                    out.append('|');
                }
            }
        }
        return out.toString();
    }
}
