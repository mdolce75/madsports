package com.fppstats.stats;

import com.fppstats.Fixtures;
import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.parse.SidgadParser;
import com.fppstats.stats.ReportData.TeamRecord;
import com.fppstats.stats.ReportData.TeamReport;
import com.fppstats.stats.StatsEngine.CompetitionData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class StatsEngineTest {

    public static final ClubView VELEZ = new ClubView(74, "CAVS", "VELEZ", List.of());
    public static final LocalDate HOY = LocalDate.of(2026, 10, 13);

    public static CompetitionData data(int idc) {
        Competition comp = SidgadParser.catalog(Fixtures.read("catalogo.html")).stream()
                .filter(c -> c.id() == idc).findFirst().orElseThrow();
        String standings = Fixtures.read("clasif_" + idc + ".html");
        return new CompetitionData(comp,
                SidgadParser.standings(standings), SidgadParser.reportDate(standings),
                SidgadParser.calendar(Fixtures.read("cal_" + idc + ".html")),
                SidgadParser.playerStats(Fixtures.read("stats_" + idc + ".html")));
    }

    @Test
    void separaLosEquiposAyBDelClub() {
        ReportData report = StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), HOY);

        assertThat(report.teams()).extracting(TeamReport::code).containsExactly("CAVS A", "CAVS B");
        // Valores calculados por separado (script Python sobre el mismo HTML).
        assertThat(team(report, "CAVS A").total()).isEqualTo(new TeamRecord(9, 8, 0, 1, 80, 8));
        assertThat(team(report, "CAVS B").total()).isEqualTo(new TeamRecord(10, 3, 0, 7, 29, 74));
    }

    @Test
    void fichaPosicionesYJugadores() {
        TeamReport a = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), HOY), "CAVS A");

        assertThat(a.standings()).singleElement().satisfies(s -> {
            assertThat(s.position()).isEqualTo(4);
            assertThat(s.points()).isEqualTo(24);
        });
        assertThat(a.total().points()).isEqualTo(24);
        assertThat(a.form()).hasSize(5);
        assertThat(a.results()).hasSize(9);
        assertThat(a.home().played() + a.away().played()).isEqualTo(9);
        assertThat(a.players()).hasSize(7);
        assertThat(a.players().stream().mapToInt(ReportData.PlayerLine::goals).sum()).isEqualTo(67);
        assertThat(a.players().getFirst().goals()).isGreaterThanOrEqualTo(a.players().get(1).goals());
        assertThat(a.players().getFirst().goalShare())
                .isCloseTo(100.0 * a.players().getFirst().goals() / 80, within(0.001));
        assertThat(a.rivals()).allSatisfy(r -> assertThat(r.played()).isPositive());
    }

    @Test
    void proximosPartidosSoloDesdeHoy() {
        TeamReport velez = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), HOY), "CAVS");

        assertThat(velez.results()).extracting(ReportData.MatchLine::rival, ReportData.MatchLine::score,
                        ReportData.MatchLine::outcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ARGENTINOS JUNIORS", "2 - 4", "P"),
                        org.assertj.core.groups.Tuple.tuple("COMUNICACIONES", "7 - 0", "G"));
        // Jornadas 3 a 5 todavía sin fecha asignada.
        assertThat(velez.upcoming()).hasSize(3)
                .allSatisfy(m -> assertThat(m.date()).isNull())
                .extracting(ReportData.MatchLine::rival).containsExactly("HURACAN", "RIVER PLATE", "GEBA B");
        assertThat(velez.upcoming().getFirst().home()).isFalse();
    }

    @Test
    void contextoDeLigaUsaTodosLosPartidosJugados() {
        ReportData report = StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), HOY);

        // 8 partidos jugados: 1-3, 1-7, 13-2, 2-4 ... promedio por equipo y partido = goles / 16
        assertThat(report.league().matchesPlayed()).isEqualTo(8);
        assertThat(report.league().goalsPerTeamGame()).isPositive();
        assertThat(report.sources()).singleElement()
                .satisfies(s -> assertThat(s.url()).endsWith("/league/779"));
    }

    @Test
    void temporadaSumaAperturaYClausuraYMarcaTablaDesactualizada() {
        // La tabla del Apertura (693) quedó congelada el 03/05/2026 con 10 PJ; el calendario tiene todos los partidos.
        TeamReport a = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Temporada",
                List.of(data(693), data(765)), HOY), "CAVS A");

        assertThat(a.byTorneo()).extracting(ReportData.TorneoLine::torneo).containsExactly("Apertura", "Clausura");
        assertThat(a.total().played())
                .isEqualTo(a.byTorneo().get(0).record().played() + a.byTorneo().get(1).record().played());
        assertThat(a.standings()).hasSize(2);
        ReportData.StandingSnapshot apertura = a.standings().getFirst();
        assertThat(apertura.publishedOn()).isEqualTo(LocalDate.of(2026, 5, 3));
        assertThat(apertura.played()).isLessThan(a.byTorneo().getFirst().record().played());
        assertThat(apertura.outdated()).isTrue();
        assertThat(a.standings().get(1).outdated()).isFalse();
    }

    @Test
    void equiposAyBSeIdentificanIgualEnTodosLosTorneos() {
        assertThat(StatsEngine.teamKey(ref("CAVS B"), 2, "CAVS")).isEqualTo("CAVS B");
        assertThat(StatsEngine.teamKey(ref("CAVS A"), 2, "CAVS")).isEqualTo("CAVS A");
        // Torneo donde el A figura sin letra.
        assertThat(StatsEngine.teamKey(ref("CAVS"), 2, "CAVS")).isEqualTo("CAVS A");
        // Único equipo del club en la competición.
        assertThat(StatsEngine.teamKey(ref("CAVS"), 1, "CAVS")).isEqualTo("CAVS");
        // SIDGAD a veces omite el espacio: "GEBAB" es el GEBA B.
        assertThat(StatsEngine.teamKey(ref("GEBAB"), 2, "GEBA")).isEqualTo("GEBA B");
        assertThat(StatsEngine.teamKey(ref("GEBA  B"), 2, "GEBA")).isEqualTo("GEBA B");
    }

    @Test
    void cruceEntreAyBDelMismoClubCuentaParaAmbos() {
        ReportData report = StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), HOY);

        assertThat(team(report, "CAVS A").rivals()).anySatisfy(r -> assertThat(r.rival()).isEqualTo("VELEZ B"));
        assertThat(team(report, "CAVS B").rivals()).anySatisfy(r -> assertThat(r.rival()).isEqualTo("VELEZ A"));
    }

    private static com.fppstats.sidgad.model.Models.TeamRef ref(String code) {
        return new com.fppstats.sidgad.model.Models.TeamRef(1, 1, code, code);
    }

    static TeamReport team(ReportData r, String code) {
        return r.teams().stream().filter(t -> t.code().equals(code)).findFirst().orElseThrow();
    }
}
