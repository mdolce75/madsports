package com.madsports.report;

import com.madsports.Fixtures;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.CategoryOption;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.report.ReportService.Request;
import com.madsports.report.ReportService.Scope;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.parse.SidgadParser;
import com.madsports.stats.ReportData;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CategoryExtractorTest {

    final Competition primera = SidgadParser.catalog(Fixtures.read("catalogo.html")).stream()
            .filter(c -> c.id() == 779).findFirst().orElseThrow();
    final List<Match> matches = SidgadParser.calendar(Fixtures.read("cal_779.html"));
    static final ClubView VELEZ = new ClubView(74, "CAVS", "VELEZ", List.of());
    static final Request REQUEST = new Request(74, "PRIMERA", "MASCULINO", Scope.SEASON, 2026, Federation.FPP);

    /** SIDGAD con los fixtures de la competición 779: actas guardadas de 3 partidos; el resto, sin acta. */
    SidgadClient sidgad(boolean standingsPublished) {
        SidgadClient sidgad = mock(SidgadClient.class);
        when(sidgad.standings(779)).thenReturn(standingsPublished ? Fixtures.read("clasif_779.html") : SidgadClient.NOT_PUBLISHED);
        when(sidgad.calendar(779)).thenReturn(Fixtures.read("cal_779.html"));
        when(sidgad.playerStats(779)).thenReturn(Fixtures.read("stats_779.html"));
        when(sidgad.gameSheet(eq(779), anyInt())).thenAnswer(i -> {
            int idp = i.getArgument(1);
            return Set.of(26389, 26390, 26396).contains(idp) ? Fixtures.read("gr_" + idp + ".html")
                    : Fixtures.read("gr_sin_acta.html");
        });
        when(sidgad.playerSeason(anyInt(), anyInt(), any()))
                .thenThrow(new SidgadClient.SidgadException("sin ficha"));
        return sidgad;
    }

    ReportData extract(SidgadClient sidgad) {
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.competitionsFor(eq(Federation.FPP), eq(74), any(), any(), eq(2026))).thenReturn(List.of(primera));
        return new CategoryExtractor(catalog, sidgad, Clock.systemUTC()).extract(REQUEST, VELEZ,
                new Category("PRIMERA", "MASCULINO"), CategoryOption.parse("PRIMERA"), m -> { });
    }

    @Test
    void readsTheCompetitionAndItsSheets() {
        ReportData d = extract(sidgad(true));

        assertThat(d.teams()).hasSize(1);
        ReportData.TeamReport t = d.teams().getFirst();
        assertThat(t.results()).hasSize((int) matches.stream().filter(m -> m.played() && m.involves(5581)).count());
        // Las dos actas de Vélez están: no falta ninguna.
        assertThat(t.timeProfile().matchesWithSheet()).isEqualTo(2);
        assertThat(d.notPublished()).isEmpty();
        assertThat(d.sources()).hasSize(1);
        assertThat(d.federation()).isEqualTo(Federation.FPP);
    }

    @Test
    void whatSidgadDidNotPublishIsListed() {
        ReportData d = extract(sidgad(false));

        assertThat(d.notPublished()).singleElement().asString().startsWith("Tabla de posiciones");
        assertThat(d.teams().getFirst().standings()).isEmpty();
    }

    @Test
    void theCrestIsTheLogoMostUsedByTheClubTeams() {
        assertThat(CategoryExtractor.logoFile(List.of(primera), 22)).isEqualTo("22_10.png");
        assertThat(CategoryExtractor.logoFile(List.of(primera), 74)).isEqualTo("74_3.png");
        assertThat(CategoryExtractor.logoFile(List.of(primera), 999)).isNull();
    }

    @Test
    void sheetsOfAllPlayedClubMatchesAreRead() {
        assertThat(CategoryExtractor.sheetsToRead(primera, matches, 74, 0))
                .extracting(Match::idp).containsExactly(26390, 26396);
    }

    @Test
    void withLastNOnlyThoseSheetsAreRead() {
        assertThat(CategoryExtractor.sheetsToRead(primera, matches, 74, 1))
                .extracting(Match::idp).containsExactly(26396);
    }
}
