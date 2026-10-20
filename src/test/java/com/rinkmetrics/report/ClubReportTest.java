package com.rinkmetrics.report;

import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.catalog.CatalogService.LevelView;
import com.rinkmetrics.sidgad.model.Competition;
import com.rinkmetrics.sidgad.model.Competition.Category;
import com.rinkmetrics.sidgad.model.Federacion;
import com.rinkmetrics.stats.ReportData;
import com.rinkmetrics.stats.ReportData.PlayerLine;
import com.rinkmetrics.stats.StatsEngine;
import com.rinkmetrics.stats.StatsEngineTest;
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
@SpringBootTest(properties = "rinkmetrics.ai.enabled=false")
class ClubReportTest {

    static final LocalDate HOY = LocalDate.of(2026, 10, 13);

    @Autowired SampleReport sample;
    @Autowired PdfRenderer renderer;

    @Test
    void ramasYCategoriasEnOrdenConSusTop3() {
        ClubReport r = sample.clubReport(HOY);
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
    void elPdfSigueRamaCategoriaYSusApartados() throws Exception {
        String html = renderer.clubHtml(sample.clubReport(HOY), true);
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
        String bloque = html.substring(primera, juvenil);
        List<String> apartados = List.of(">Partidos</h3>", ">Goles</h3>", ">Faltas</h3>", ">Tarjetas</h3>",
                ">Top 3 · Promedio de gol</h3>", ">Top 3 · Disciplina</h3>", ">Top 3 · Libres directos</h3>",
                ">Top 3 · Penales</h3>", ">Fortalezas y debilidades</h3>");
        int previo = -1;
        for (String a : apartados) {
            int i = bloque.indexOf(a);
            assertThat(i).as(a).isGreaterThan(previo);
            previo = i;
        }
        assertThat(bloque).contains("Efectividad", "Por partido", "Amarillas", "Azules", "Rojas", "Aspectos a mejorar");

        byte[] pdf = renderer.clubPdf(sample.clubReport(HOY), true);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        Files.write(Path.of("target", "informe-club.pdf"), pdf);
        Files.writeString(Path.of("target", "informe-club.html"), html);
    }

    @Test
    void lasAmarillasDeCadaJugadorSalenDeLasActas() {
        ReportData d = StatsEngine.build(StatsEngineTest.VELEZ, "Primera Masculino", "Clausura",
                List.of(StatsEngineTest.data(779)), HOY);
        ReportData.TeamReport t = d.teams().getFirst();
        int enActas = t.timeProfile().byPeriod().stream().mapToInt(ReportData.TimeBucket::yellowOwn).sum()
                + t.timeProfile().unrecorded().yellowOwn();
        assertThat(t.squad().stream().mapToInt(PlayerLine::yellowCards).sum()).isEqualTo(enActas);
    }

    @Test
    void lasCategoriasDelClubPrimeroMasculinasYSoloLasQueJuegan() {
        CatalogService catalog = mock(CatalogService.class);
        ReportService service = new ReportService(catalog, null, null, null, null, null, java.time.Clock.systemUTC());
        ClubView club = new ClubView(74, "CAVS", "VELEZ", List.of(
                new LevelView("INFANTIL:A", "Infantil A", List.of("MASCULINO", "FEMENINO")),
                new LevelView("INFANTIL:B", "Infantil B", List.of("MASCULINO")),
                new LevelView("PRIMERA", "Primera", List.of("MASCULINO", "FEMENINO"))));
        when(catalog.category(eq(Federacion.FPP), any(), any()))
                .thenAnswer(i -> new Category(i.getArgument(1), i.getArgument(2)));
        Competition jugada = mock(Competition.class);
        // Primera Femenino figura en el club pero no juega en el período: queda afuera.
        when(catalog.competitionsFor(eq(Federacion.FPP), eq(74), any(), any(), anyInt())).thenAnswer(i ->
                i.getArgument(2).equals(new Category("PRIMERA", "FEMENINO")) ? List.of() : List.of(jugada));

        List<ReportService.ClubCategory> todas = service.clubCategories(club, Federacion.FPP,
                ReportService.Scope.TEMPORADA, 2026);
        assertThat(todas).extracting(c -> c.levelLabel() + " " + c.category().branchLabel())
                .containsExactly("Infantil A Masculino", "Infantil B Masculino", "Primera Masculino", "Infantil A Femenino");
    }
}
