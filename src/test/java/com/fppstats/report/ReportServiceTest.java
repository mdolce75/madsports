package com.fppstats.report;

import com.fppstats.Fixtures;
import com.fppstats.report.ReportService.Scope;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Models.Match;
import com.fppstats.sidgad.parse.SidgadParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportServiceTest {

    final Competition primera = SidgadParser.catalog(Fixtures.read("catalogo.html")).stream()
            .filter(c -> c.id() == 779).findFirst().orElseThrow();
    final List<Match> matches = SidgadParser.calendar(Fixtures.read("cal_779.html"));

    @Test
    void seLeenLasActasDeTodosLosPartidosJugadosDelClub() {
        assertThat(ReportService.sheetsToRead(primera, matches, 74, 0))
                .extracting(Match::idp).containsExactly(26390, 26396);
    }

    @Test
    void conUltimosNSoloSeLeenEsasActas() {
        assertThat(ReportService.sheetsToRead(primera, matches, 74, 1))
                .extracting(Match::idp).containsExactly(26396);
    }

    @Test
    void periodos() {
        assertThat(Scope.parse(null)).isEqualTo(Scope.TEMPORADA);
        assertThat(Scope.parse("ultimos_5").lastMatches()).isEqualTo(5);
        assertThat(Scope.parse("APERTURA").torneo()).isEqualTo(Competition.Torneo.APERTURA);
        assertThat(Scope.TEMPORADA.torneo()).isNull();
        assertThatThrownBy(() -> Scope.parse("PLAYOFF")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Período inválido");
    }
}
