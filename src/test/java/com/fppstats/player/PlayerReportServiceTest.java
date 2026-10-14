package com.fppstats.player;

import com.fppstats.Fixtures;
import com.fppstats.player.PlayerReport.Line;
import com.fppstats.player.PlayerReportService.Query;
import com.fppstats.player.PlayerReportService.TeamInput;
import com.fppstats.report.ReportService.Scope;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Models.LineupPlayer;
import com.fppstats.sidgad.model.Models.Match;
import com.fppstats.sidgad.parse.SidgadParser;
import com.fppstats.stats.ReportData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Con las actas reales del Clausura 2026 de Primera Masculino (idc 779): fecha 1 y 2 de Vélez. */
class PlayerReportServiceTest {

    static final List<String> LEVELS = List.of("INFANTIL", "PREJUVENIL", "JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");
    static final ReportData.ClubInfo VELEZ = new ReportData.ClubInfo(74, "CAVS", "VELEZ");
    static final LocalDate HOY = LocalDate.of(2026, 10, 13);

    final Competition primera = SidgadParser.catalog(Fixtures.read("catalogo.html")).stream()
            .filter(c -> c.id() == 779).findFirst().orElseThrow();
    final List<Match> calendar = SidgadParser.calendar(Fixtures.read("cal_779.html"));

    /** Partidos jugados del equipo en el calendario, con la planilla de su lado en los que hay acta en los fixtures. */
    TeamInput team(int teamId, String category, Map<Integer, String> sheets) {
        List<Match> matches = PlayerReportService.teamMatches(calendar, teamId);
        Map<Integer, List<LineupPlayer>> lineups = new LinkedHashMap<>();
        for (Match m : matches) {
            String html = sheets.getOrDefault(m.idp(), "File not found.");
            lineups.put(m.idp(), RosterService.ownSide(SidgadParser.lineups(html), m, Set.of(teamId)));
        }
        return new TeamInput(primera, category, teamId, matches, lineups);
    }

    Map<Integer, String> velezSheets() {
        return Map.of(26390, Fixtures.read("gr_26390.html"), 26396, Fixtures.read("gr_26396.html"));
    }

    static Query query(int playerId, String name) {
        return new Query(playerId, name, "Usuario De Prueba", 74, 2026, Scope.CLAUSURA);
    }

    @Test
    void partidosGolesYTarjetasDesdeLasActas() {
        PlayerReport r = PlayerReportService.assemble(query(3851, "Ignacio J Graiño"), VELEZ, HOY, LEVELS,
                List.of(team(5581, "Primera Masculino", velezSheets())));

        // Vélez 2-4 Argentinos (2 goles de Graiño) y 7-0 Comunicaciones (2 goles).
        assertThat(r.categories()).containsExactly(new Line("Primera Masculino", 2, 1, 0, 1, 4, 0, 0, 0, 0));
        assertThat(r.total()).isEqualTo(new Line("Total", 2, 1, 0, 1, 4, 0, 0, 0, 0));
        assertThat(r.total().goalsPerGame()).isEqualTo(2.0);
        assertThat(r.matches()).extracting(PlayerReport.MatchRow::rival)
                .containsExactly("ARGENTINOS JUNIORS", "COMUNICACIONES");
        assertThat(r.matches()).extracting(PlayerReport.MatchRow::result).containsExactly("P", "G");
        assertThat(r.notes()).isEmpty();
    }

    @Test
    void tarjetasDelVisitanteYPartidosSinActa() {
        // Argentinos (visitante en el 26390) jugó otro partido cuya acta no está en los fixtures: queda sin contar.
        int argentinos = calendar.stream().filter(m -> m.idp() == 26390).findFirst().orElseThrow().awayTeamId();
        PlayerReport r = PlayerReportService.assemble(query(2698, "Nicolas Ortin"), VELEZ, HOY, LEVELS,
                List.of(team(argentinos, "Primera Masculino", velezSheets())));

        Line l = r.categories().getFirst();
        assertThat(l.played()).isEqualTo(1);
        assertThat(l.won()).isEqualTo(1);
        assertThat(l.yellowCards()).isEqualTo(1);
        assertThat(l.cardsPerGame()).isEqualTo(1.0);
        assertThat(l.withoutSheet()).isEqualTo(PlayerReportService.teamMatches(calendar, argentinos).size() - 1);
        if (l.withoutSheet() > 0) {
            assertThat(r.notes()).singleElement().asString().contains("no tiene", "acta publicada");
        }
    }

    @Test
    void lasCategoriasSeOrdenanPorNivelYSeSumanEnElTotal() {
        PlayerReport r = PlayerReportService.assemble(query(3851, "Ignacio J Graiño"), VELEZ, HOY, LEVELS, List.of(
                team(5581, "Primera Masculino", velezSheets()),
                new TeamInput(primera, "Juniors Masculino", 5581,
                        PlayerReportService.teamMatches(calendar, 5581), Map.of(26390,
                        RosterService.ownSide(SidgadParser.lineups(Fixtures.read("gr_26390.html")),
                                calendar.stream().filter(m -> m.idp() == 26390).findFirst().orElseThrow(), Set.of(5581))))));

        // La segunda entrada simula otra categoría con la misma competición del fixture: a igual nivel, por nombre.
        assertThat(r.categories()).extracting(Line::category).containsExactly("Juniors Masculino", "Primera Masculino");
        assertThat(r.total().played()).isEqualTo(r.categories().stream().mapToInt(Line::played).sum());
        assertThat(r.total().goals()).isEqualTo(6);
    }

    @Test
    void siNoFiguraLoInforma() {
        PlayerReport r = PlayerReportService.assemble(query(1, "Nadie"), VELEZ, HOY, LEVELS,
                List.of(team(5581, "Primera Masculino", velezSheets())));
        assertThat(r.hasData()).isFalse();
        assertThat(r.categories()).isEmpty();
        assertThat(r.notes()).singleElement().asString().contains("Nadie no figura", "VELEZ", "2026");
    }

    @Test
    void elPlantelSaleDeLasPlanillasDelEquipo() {
        Match m = calendar.stream().filter(x -> x.idp() == 26390).findFirst().orElseThrow();
        List<RosterService.RosterPlayer> roster = RosterService.roster(
                RosterService.ownSide(SidgadParser.lineups(Fixtures.read("gr_26390.html")), m, Set.of(5581)),
                "Primera Masculino");
        // Los 9 citados de Vélez (la tabla de estadísticas solo lista a los goleadores).
        assertThat(roster).hasSize(9).extracting(RosterService.RosterPlayer::id).contains(3851, 4156, 5888);
        assertThat(roster).extracting(RosterService.RosterPlayer::name).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    @Test
    void nombreDeArchivo() {
        assertThat(PlayerReportService.slug("Ignacio J Graiño")).isEqualTo("ignacio-j-graino");
    }
}
