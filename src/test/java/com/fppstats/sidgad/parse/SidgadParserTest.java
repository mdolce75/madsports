package com.fppstats.sidgad.parse;

import com.fppstats.Fixtures;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Models.EventType;
import com.fppstats.sidgad.model.Models.Match;
import com.fppstats.sidgad.model.Models.MatchEvent;
import com.fppstats.sidgad.model.Models.PlayerStats;
import com.fppstats.sidgad.model.Models.StandingRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SidgadParserTest {

    @Test
    void catalogoReconoceCompeticionesFppConSusEquipos() {
        List<Competition> all = SidgadParser.catalog(Fixtures.read("catalogo.html"));

        List<Competition> fpp2026 = all.stream().filter(c -> c.federationId() == 3 && c.seasonId() == 40).toList();
        assertThat(fpp2026).hasSize(32).allSatisfy(c -> assertThat(c.category()).isNotNull());

        Competition primera = all.stream().filter(c -> c.id() == 779).findFirst().orElseThrow();
        assertThat(primera.torneo()).isEqualTo(Competition.Torneo.CLAUSURA);
        assertThat(primera.category().key()).isEqualTo("PRIMERA_MASCULINO");
        assertThat(primera.teams()).hasSize(11)
                .anySatisfy(t -> {
                    assertThat(t.teamId()).isEqualTo(5581);
                    assertThat(t.clubId()).isEqualTo(74);
                    assertThat(t.code()).isEqualTo("CAVS");
                    assertThat(t.name()).isEqualTo("VELEZ");
                })
                .anySatisfy(t -> assertThat(t.name()).isEqualTo("ESTUDIANTIL PORTEÑO"));

        Competition div2 = all.stream().filter(c -> c.id() == 737).findFirst().orElseThrow();
        assertThat(div2.division()).isEqualTo(2);
        assertThat(div2.category().key()).isEqualTo("PRIMERA_FEMENINO");
    }

    @Test
    void clasificacionSeparaGrupos() {
        List<StandingRow> rows = SidgadParser.standings(Fixtures.read("clasif_779.html"));

        assertThat(rows).extracting(StandingRow::group).containsOnly("IMPAR", "PAR");
        StandingRow velez = rows.stream().filter(r -> r.code().equals("CAVS")).findFirst().orElseThrow();
        assertThat(velez.clubId()).isEqualTo(74);
        assertThat(velez.name()).isEqualTo("VELEZ");
    }

    @Test
    void clasificacionSinGrupoLeeTodasLasColumnas() {
        StandingRow velezA = SidgadParser.standings(Fixtures.read("clasif_765.html")).stream()
                .filter(r -> r.code().equals("CAVS A")).findFirst().orElseThrow();

        assertThat(velezA).isEqualTo(new StandingRow("", 4, 74, "VELEZ A", "CAVS A",
                24, 9, 8, 0, 1, 80, 8, 72));
    }

    @Test
    void calendarioLeeJornadasResultadosYPendientes() {
        List<Match> matches = SidgadParser.calendar(Fixtures.read("cal_779.html"));

        assertThat(matches).hasSize(25);
        assertThat(matches.stream().filter(Match::played)).hasSize(8);
        Match first = matches.getFirst();
        assertThat(first.idp()).isEqualTo(26393);
        assertThat(first.round()).isEqualTo(1);
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(first.time()).isEqualTo(LocalTime.of(17, 0));
        assertThat(first.homeName()).isEqualTo("SAN LORENZO");
        assertThat(first.homeTeamId()).isEqualTo(5588);
        assertThat(first.awayName()).isEqualTo("CIUDAD");
        assertThat(first.homeGoals()).isEqualTo(1);
        assertThat(first.awayGoals()).isEqualTo(3);
        assertThat(matches).extracting(Match::round).contains(5);
    }

    @Test
    void estadisticasPorJugador() {
        List<PlayerStats> players = SidgadParser.playerStats(Fixtures.read("stats_765.html"));

        PlayerStats top = players.getFirst();
        assertThat(top.name()).isEqualTo("Martina Lorena Granato");
        assertThat(top.teamId()).isEqualTo(5476);
        assertThat(top.teamCode()).isEqualTo("HARRO");
        assertThat(top.goals()).isEqualTo(53);
        assertThat(top.games()).isEqualTo(7);
        assertThat(top.penaltiesScored()).isZero();
        assertThat(top.penaltiesTaken()).isEqualTo(1);
        assertThat(top.directFreeScored()).isEqualTo(1);
        assertThat(top.directFreeTaken()).isEqualTo(1);

        assertThat(players.get(1).blueCards()).isEqualTo(2);
        assertThat(players.stream().filter(p -> p.teamId() == 5455).mapToInt(PlayerStats::goals).sum()).isEqualTo(67);
    }

    @Test
    void actaDePartidoConGolesYTarjetas() {
        List<MatchEvent> events = SidgadParser.gameEvents(Fixtures.read("gr_26390.html"));

        assertThat(events).filteredOn(e -> e.type() == EventType.GOAL).hasSize(6);
        assertThat(events).filteredOn(e -> e.type() == EventType.GOAL && e.teamId() == 5581).hasSize(2);
        assertThat(events).filteredOn(e -> e.type() == EventType.YELLOW_CARD).singleElement().satisfies(e -> {
            assertThat(e.period()).isEqualTo(1);
            assertThat(e.minute()).isEqualTo(13);
            assertThat(e.second()).isEqualTo(51);
            assertThat(e.teamId()).isEqualTo(5589);
            assertThat(e.teamCode()).isEqualTo("AAAJ");
            assertThat(e.clubId()).isEqualTo(94);
        });
    }

    @Test
    void actaConTarjetasAzulesYAmarillasDistintas() {
        // Acta 26389 (779, Huracán 13-2 GEBA B): 15 goles, 4 azules y 2 amarillas.
        List<MatchEvent> events = SidgadParser.gameEvents(Fixtures.read("gr_26389.html"));

        assertThat(events).filteredOn(e -> e.type() == EventType.GOAL).hasSize(15);
        assertThat(events).filteredOn(e -> e.type() == EventType.BLUE_CARD).hasSize(4);
        assertThat(events).filteredOn(e -> e.type() == EventType.YELLOW_CARD).hasSize(2);
        assertThat(events).filteredOn(e -> e.type() == EventType.RED_CARD).isEmpty();
        // Faltas, tiempos muertos y penales no son eventos analizados.
        assertThat(events).hasSize(21);
    }

    @Test
    void actaNoPublicada() {
        assertThat(SidgadParser.gameEvents(Fixtures.read("gr_sin_acta.html"))).isEmpty();
    }

    @Test
    void duracionDePeriodoDesdeConfigParams() {
        assertThat(SidgadParser.periodMinutes("ids_teams:1,2;ids_period_reg:1,2;tpo_reg:20;ids_period_ot:0;tpo_ot:0;"))
                .isEqualTo(20);
        assertThat(SidgadParser.periodMinutes("")).isZero();
        Competition infantil = SidgadParser.catalog(Fixtures.read("catalogo.html")).stream()
                .filter(c -> c.id() == 765).findFirst().orElseThrow();
        assertThat(infantil.periodMinutes()).isEqualTo(20);
    }

    @Test
    void nombreDeJugadorQuedaNombreApellido() {
        assertThat(SidgadParser.playerName("GRAIÑO, IGNACIO J")).isEqualTo("Ignacio J Graiño");
        assertThat(SidgadParser.playerName("SOLO")).isEqualTo("Solo");
    }
}
