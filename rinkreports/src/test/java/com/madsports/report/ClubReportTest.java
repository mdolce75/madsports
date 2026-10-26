package com.madsports.report;

import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.catalog.CatalogService.LevelView;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Federation;
import com.madsports.stats.ReportData;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.StatsEngine;
import com.madsports.stats.StatsEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Informe del club completo: por rama y, dentro de la rama, por categoría, con sus cifras y sus top 3. */
@SpringBootTest(classes = ReportsTestConfig.class)
class ClubReportTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Autowired SampleReport sample;
    @Autowired PdfRenderer renderer;

    @Test
    void branchesAndCategoriesInOrderWithTheirTop3() {
        ClubReport r = sample.clubReport(TODAY);
        assertThat(r.branches()).extracting(ClubReport.Branch::name).containsExactly("Masculino", "Femenino");
        assertThat(r.branches().getFirst().categories()).extracting(ClubReport.CategoryBlock::name)
                .containsExactly("Primera", "Juvenil");

        for (ClubReport.Branch b : r.branches()) {
            for (ClubReport.CategoryBlock k : b.categories()) {
                // Goleo: por promedio de gol, de mayor a menor, hasta 3, con goles y partidos.
                assertThat(k.topScorers()).hasSizeLessThanOrEqualTo(3)
                        .isSortedAccordingTo((x, y) -> Double.compare(y.value(), x.value()))
                        .allSatisfy(x -> {
                            assertThat(x.count()).isEqualTo(x.player().goals());
                            assertThat(x.of()).isEqualTo(x.player().games());
                        });
                // Libres y penales: por efectividad, con convertidos de tirados.
                assertThat(k.topDirectFree()).isSortedAccordingTo((x, y) -> Double.compare(y.value(), x.value()))
                        .allSatisfy(x -> assertThat(x.value()).isEqualTo(100.0 * x.count() / x.of()));
                assertThat(k.topPenalties()).hasSizeLessThanOrEqualTo(3)
                        .allSatisfy(x -> assertThat(x.of()).isEqualTo(x.player().penaltiesTaken()));
                // Disciplina: todos los colores sumados, de mayor a menor.
                assertThat(k.topDiscipline()).hasSizeLessThanOrEqualTo(3)
                        .isSortedAccordingTo((x, y) -> Integer.compare(y.cards(), x.cards()));
                // Las amarillas del equipo son las de sus jugadores; las faltas, de las actas.
                assertThat(k.yellowCards()).isEqualTo(k.team().squad().stream().mapToInt(PlayerLine::yellowCards).sum());
                assertThat(k.sheetMatches()).isLessThanOrEqualTo(k.record().played());
                assertThat(k.analysis().summary()).isNotBlank();
            }
        }
        assertThat(r.branches().getFirst().categories().getFirst().yellowCards()).isPositive();
    }

    @Test
    void thePdfFollowsBranchCategoryAndSections() throws Exception {
        String html = renderer.clubHtml(sample.clubReport(TODAY), true);
        int masc = html.indexOf("Rama <span>Masculino</span>");
        int primera = html.indexOf(">Primera Masculino</h2>");
        int juvenil = html.indexOf(">Juvenil Masculino</h2>");
        int fem = html.indexOf("Rama <span>Femenino</span>");
        int infantil = html.indexOf(">Infantil Femenino</h2>");
        assertThat(masc).isPositive().isLessThan(primera);
        assertThat(primera).isLessThan(juvenil);
        assertThat(juvenil).isLessThan(fem);
        assertThat(fem).isLessThan(infantil);
        // Los apartados de cada categoría, en orden.
        String block = html.substring(primera, juvenil);
        List<String> sections = List.of(">Partidos</h3>", ">Goles</h3>", ">Faltas</h3>", ">Tarjetas</h3>",
                ">Top 3 · Promedio de gol</h3>", ">Top 3 · Disciplina</h3>", ">Top 3 · Libres directos</h3>",
                ">Top 3 · Penales</h3>", ">Fortalezas y debilidades</h3>");
        int previous = -1;
        for (String a : sections) {
            int i = block.indexOf(a);
            assertThat(i).as(a).isGreaterThan(previous);
            previous = i;
        }
        assertThat(block).contains("Efectividad", "Por partido", "Amarillas", "Azules", "Rojas", "Aspectos a mejorar");

        byte[] pdf = renderer.clubPdf(sample.clubReport(TODAY), true);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        Files.write(Path.of("target", "informe-club.pdf"), pdf);
        Files.writeString(Path.of("target", "informe-club.html"), html);
    }

    @Test
    void eachPlayerYellowsComeFromTheSheets() {
        ReportData d = StatsEngine.build(StatsEngineTest.VELEZ, "Primera Masculino", "Clausura",
                List.of(StatsEngineTest.data(779)), TODAY);
        ReportData.TeamReport t = d.teams().getFirst();
        int inSheets = t.timeProfile().byPeriod().stream().mapToInt(ReportData.TimeBucket::yellowOwn).sum()
                + t.timeProfile().unrecorded().yellowOwn();
        assertThat(t.squad().stream().mapToInt(PlayerLine::yellowCards).sum()).isEqualTo(inSheets);
    }

    @Test
    void clubCategoriesMaleFirstAndOnlyThoseThatPlay() {
        CatalogService catalog = mock(CatalogService.class);
        ReportService service = new ReportService(catalog, null, null, null, null, null, java.time.Clock.systemUTC());
        ClubView club = new ClubView(74, "CAVS", "VELEZ", List.of(
                new LevelView("INFANTIL:A", "Infantil A", List.of("MASCULINO", "FEMENINO")),
                new LevelView("INFANTIL:B", "Infantil B", List.of("MASCULINO")),
                new LevelView("PRIMERA", "Primera", List.of("MASCULINO", "FEMENINO"))));
        when(catalog.category(eq(Federation.FPP), any(), any()))
                .thenAnswer(i -> new Category(i.getArgument(1), i.getArgument(2)));
        Competition played = mock(Competition.class);
        // Primera Femenino figura en el club pero no juega en el período: queda afuera.
        when(catalog.competitionsFor(eq(Federation.FPP), eq(74), any(), any(), anyInt())).thenAnswer(i ->
                i.getArgument(2).equals(new Category("PRIMERA", "FEMENINO")) ? List.of() : List.of(played));

        List<ReportService.ClubCategory> all = service.clubCategories(club, Federation.FPP,
                ReportService.Scope.SEASON, 2026);
        assertThat(all).extracting(c -> c.levelLabel() + " " + c.category().branchLabel())
                .containsExactly("Infantil A Masculino", "Infantil B Masculino", "Primera Masculino", "Infantil A Femenino");

        // Solo las elegidas, de una o de las dos ramas, en el mismo orden (Masculino primero).
        assertThat(ReportService.select(all, List.of("infantil:a|femenino", "PRIMERA|MASCULINO")))
                .extracting(c -> c.levelLabel() + " " + c.category().branchLabel())
                .containsExactly("Primera Masculino", "Infantil A Femenino");
        assertThat(ReportService.select(all, List.of("INFANTIL:A|MASCULINO", "INFANTIL:B|MASCULINO", "PRIMERA|MASCULINO")))
                .allMatch(c -> c.category().branch().equals("MASCULINO")).hasSize(3);
        // Una que el club no juega en el período no suma nada.
        assertThat(ReportService.select(all, List.of("PRIMERA|FEMENINO"))).isEmpty();
    }
}
