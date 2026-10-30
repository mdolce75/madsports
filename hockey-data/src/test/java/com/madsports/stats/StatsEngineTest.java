package com.madsports.stats;

import com.madsports.Fixtures;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Models;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.parse.SidgadParser;
import com.madsports.stats.ReportData.TeamRecord;
import com.madsports.stats.ReportData.TeamReport;
import com.madsports.stats.ReportData.TimeBucket;
import com.madsports.stats.ReportData.TimeProfile;
import com.madsports.stats.StatsEngine.CompetitionData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class StatsEngineTest {

    public static final ClubView VELEZ = new ClubView(74, "CAVS", "VELEZ", List.of());
    public static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    public static CompetitionData data(int idc) {
        Competition comp = SidgadParser.catalog(Fixtures.read("catalogo.html")).stream()
                .filter(c -> c.id() == idc).findFirst().orElseThrow();
        String standings = Fixtures.read("clasif_" + idc + ".html");
        List<Match> matches = SidgadParser.calendar(Fixtures.read("cal_" + idc + ".html"));
        // Actas guardadas (779): los dos partidos de Vélez y Huracán 13-2 GEBA B. El resto, sin acta.
        Map<Integer, List<MatchEvent>> sheets = new HashMap<>();
        for (Match m : matches) {
            if (m.played() && (m.idp() == 26390 || m.idp() == 26396 || m.idp() == 26389)) {
                sheets.put(m.idp(), SidgadParser.gameEvents(Fixtures.read("gr_" + m.idp() + ".html")));
            }
        }
        return new CompetitionData(comp, SidgadParser.standings(standings), SidgadParser.reportDate(standings),
                matches, SidgadParser.playerStats(Fixtures.read("stats_" + idc + ".html")), sheets);
    }

    @Test
    void goalsAndCardsByFiveMinuteBuckets() {
        TimeProfile tp = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), TODAY), "CAVS")
                .timeProfile();

        // Vélez 2-4 Argentinos (26390) y Vélez 7-0 Comunicaciones (26396); períodos de 25'.
        assertThat(tp.periodMinutes()).isEqualTo(25);
        assertThat(tp.matchesPlayed()).isEqualTo(2);
        assertThat(tp.matchesWithSheet()).isEqualTo(2);
        assertThat(tp.goalMismatches()).isZero();
        assertThat(tp.buckets()).hasSize(10);
        assertThat(tp.buckets()).extracting(TimeBucket::label)
                .startsWith("1T 0-5'", "1T 5-10'").endsWith("2T 20-25'");
        assertThat(tp.buckets()).extracting(TimeBucket::goalsFor).containsExactly(0, 2, 0, 0, 0, 2, 1, 2, 1, 1);
        assertThat(tp.buckets()).extracting(TimeBucket::goalsAgainst).containsExactly(1, 1, 0, 1, 0, 0, 0, 0, 1, 0);
        // Amarilla a un jugador de Argentinos en el 13:51 del 1T: es tarjeta del rival, no propia.
        assertThat(tp.buckets().get(2).yellowRival()).isEqualTo(1);
        assertThat(tp.buckets()).allSatisfy(b -> {
            assertThat(b.cardsOwn()).isZero();
            assertThat(b.blueRival() + b.redRival()).isZero();
        });
        assertThat(tp.byPeriod()).extracting(TimeBucket::goalsFor).containsExactly(2, 7);
        assertThat(tp.byPeriod()).extracting(TimeBucket::goalsAgainst).containsExactly(3, 1);
    }

    @Test
    void lastFiveMatchesTrimRecordAndResultsButNotTournamentAverages() {
        List<CompetitionData> season = List.of(data(693), data(765));
        TeamReport full = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Temporada", season, TODAY), "CAVS A");
        ReportData lastFiveReport = StatsEngine.build(VELEZ, "Infantil Femenino", "Últimos 5", season, TODAY, 5);
        TeamReport last5 = team(lastFiveReport, "CAVS A");

        assertThat(lastFiveReport.partial()).isTrue();
        assertThat(last5.total().played()).isEqualTo(5);
        assertThat(last5.results()).containsExactlyElementsOf(full.results().subList(full.results().size() - 5, full.results().size()));
        assertThat(last5.form()).isEqualTo(full.form());
        assertThat(last5.byTournament()).extracting(ReportData.TournamentLine::tournament).containsExactly("Clausura");
        assertThat(last5.rivals().stream().mapToInt(ReportData.RivalLine::played).sum()).isEqualTo(5);
        // Jugadores son del torneo completo: no cambian con el recorte.
        assertThat(last5.players()).isEqualTo(full.players());
        // Las tarjetas sí se recortan: salen de las fichas de esos partidos (acá no hay fichas cargadas).
        assertThat(full.discipline().fromSheets()).isFalse();
        assertThat(last5.discipline().fromSheets()).isTrue();
        assertThat(last5.discipline().matches()).isZero();
        // La tabla publicada se sigue comparando contra todos los partidos del calendario.
        assertThat(last5.standings()).isEqualTo(full.standings());
        // Los próximos partidos no dependen del recorte.
        assertThat(last5.upcoming()).isEqualTo(full.upcoming());
    }

    @Test
    void withLastMatchesCardsAreCountedInThoseSheets() {
        ClubView geba = new ClubView(76, "GEBA", "GEBA", List.of());
        TeamReport gebaB = team(StatsEngine.build(geba, "Primera Masculino", "Últimos 5", List.of(data(779)), TODAY, 5), "GEBA B");

        // Ficha de Huracán 13-2 GEBA B: GEBA B recibió 1 amarilla y 4 azules (las de Huracán son del rival).
        ReportData.Discipline d = gebaB.discipline();
        assertThat(d.fromSheets()).isTrue();
        assertThat(d.matches()).isEqualTo(1);
        assertThat(d.yellowCards()).isEqualTo(1);
        assertThat(d.blueCards()).isEqualTo(4);
        assertThat(d.redCards()).isZero();
        // Az + Rj por partido con ficha, comparable con el promedio de la liga.
        assertThat(d.cardsPerGame()).isEqualTo(4.0);
        assertThat(gebaB.timeProfile().byPeriod().stream().mapToInt(TimeBucket::yellowRival).sum()).isEqualTo(1);

        // Sin recorte, la disciplina sale de las estadísticas del torneo (sin amarillas).
        ReportData.Discipline season = team(StatsEngine.build(geba, "Primera Masculino", "Clausura",
                List.of(data(779)), TODAY), "GEBA B").discipline();
        assertThat(season.fromSheets()).isFalse();
        assertThat(season.yellowKnown()).isFalse();
    }

    @Test
    void timeShortAndUpByProfiles() {
        // Huracán 13-2 GEBA B (26389): 4 azules a GEBA B (1T 18:21, 2T 01:05, 14:18, 22:37), sin superposición.
        ClubView geba = new ClubView(76, "GEBA", "GEBA", List.of());
        TimeProfile gebaB = team(StatsEngine.build(geba, "Primera Masculino", "Clausura", List.of(data(779)), TODAY), "GEBA B")
                .timeProfile();
        assertThat(gebaB.shortHandedSeconds()).isEqualTo(4 * 120);
        assertThat(gebaB.powerPlaySeconds()).isZero();
        assertThat(gebaB.shortHandedMinutesPerGame()).isEqualTo(8.0);

        ClubView huracan = new ClubView(22, "HURA", "HURACAN", List.of());
        TimeProfile hura = team(StatsEngine.build(huracan, "Primera Masculino", "Clausura", List.of(data(779)), TODAY), "HURA")
                .timeProfile();
        assertThat(hura.shortHandedSeconds()).isZero();
        assertThat(hura.powerPlaySeconds()).isEqualTo(4 * 120);
        // Las amarillas no dejan con uno menos.
        assertThat(hura.byPeriod().stream().mapToInt(TimeBucket::yellowOwn).sum()).isEqualTo(1);
    }

    @Test
    void cardsAtHalfTimeAndAfterTheMatch() {
        CompetitionData full = data(779);
        // Vélez 7-0 Comunicaciones (26396; Vélez 5581, Comunicaciones 5586) más dos sanciones agregadas:
        // azul a Comunicaciones en el descanso (P10) y roja a Vélez después del partido (P11).
        List<MatchEvent> events = new java.util.ArrayList<>(full.sheets().get(26396));
        events.add(new MatchEvent(10, 0, 0, Models.EventType.BLUE_CARD, 5586, 69, "COMU", ""));
        events.add(new MatchEvent(11, 0, 0, Models.EventType.RED_CARD, 5581, 74, "CAVS", ""));
        Map<Integer, List<MatchEvent>> sheets = new HashMap<>(full.sheets());
        sheets.put(26396, events);
        CompetitionData cd = new CompetitionData(full.competition(), full.standings(), full.standingsDate(),
                full.matches(), full.players(), sheets);

        TeamReport velez = team(StatsEngine.build(VELEZ, "Primera Masculino", "Últimos 5", List.of(cd), TODAY, 5), "CAVS");
        TimeProfile tp = velez.timeProfile();

        // Descanso: cuenta en 2T 0-5' y da 2' con uno más desde el inicio del 2T.
        assertThat(tp.buckets().get(5).blueRival()).isEqualTo(1);
        assertThat(tp.powerPlaySeconds()).isEqualTo(120);
        // Después del partido: la roja se cuenta (sin minuto) pero no deja con uno menos.
        assertThat(tp.unrecorded().redOwn()).isEqualTo(1);
        assertThat(tp.hasUnrecordedCards()).isTrue();
        assertThat(tp.shortHandedSeconds()).isZero();
        assertThat(velez.discipline().redCards()).isEqualTo(1);
        // Los goles siguen cerrando con el récord.
        assertThat(tp.totalGoalsFor()).isEqualTo(velez.total().goalsFor());
    }

    @Test
    void lastMatchesLimitTheMinuteProfile() {
        TimeProfile tp = team(StatsEngine.build(VELEZ, "Primera Masculino", "Últimos 1", List.of(data(779)), TODAY, 1), "CAVS")
                .timeProfile();

        // Solo el último partido: Vélez 7-0 Comunicaciones.
        assertThat(tp.matchesPlayed()).isEqualTo(1);
        assertThat(tp.byPeriod()).extracting(TimeBucket::goalsFor).containsExactly(1, 6);
        assertThat(tp.byPeriod()).extracting(TimeBucket::goalsAgainst).containsExactly(0, 0);
    }

    @Test
    void goalsWithoutMinuteMakeTheTableAddUpToTheRecord() {
        // Todas las fichas: nada sin minuto.
        TeamReport fullName = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), TODAY), "CAVS");
        assertThat(fullName.timeProfile().hasUnrecordedGoals()).isFalse();
        assertTotalsMatchRecord(fullName);

        // Sin ninguna ficha: todo el resultado queda sin minuto.
        TeamReport withoutProfiles = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), TODAY), "CAVS A");
        assertThat(withoutProfiles.timeProfile().unrecorded().goalsFor()).isEqualTo(80);
        assertThat(withoutProfiles.timeProfile().unrecorded().goalsAgainst()).isEqualTo(8);
        assertTotalsMatchRecord(withoutProfiles);
    }

    @Test
    void matchWithoutOrIncompleteProfileGoesToUnrecorded() {
        CompetitionData full = data(779);
        Map<Integer, List<MatchEvent>> sheets = new HashMap<>();
        // 26390 (Vélez 2-4 Argentinos): sin ficha.
        // 26396 (Vélez 7-0 Comunicaciones): ficha a la que le falta un gol de Vélez.
        List<MatchEvent> incomplete = new java.util.ArrayList<>(full.sheets().get(26396));
        incomplete.remove(incomplete.stream().filter(e -> e.type() == Models.EventType.GOAL).findFirst().orElseThrow());
        sheets.put(26396, incomplete);
        CompetitionData partial = new CompetitionData(full.competition(), full.standings(), full.standingsDate(),
                full.matches(), full.players(), sheets);

        TeamReport velez = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(partial), TODAY), "CAVS");
        TimeProfile tp = velez.timeProfile();

        assertThat(tp.matchesWithSheet()).isEqualTo(1);
        assertThat(tp.goalMismatches()).isEqualTo(1);
        // 2 + 4 del partido sin ficha, más el gol que falta en la ficha incompleta.
        assertThat(tp.unrecorded().goalsFor()).isEqualTo(3);
        assertThat(tp.unrecorded().goalsAgainst()).isEqualTo(4);
        assertThat(tp.unrecorded().cardsOwn() + tp.unrecorded().cardsRival()).isZero();
        assertTotalsMatchRecord(velez);
    }

    private static void assertTotalsMatchRecord(TeamReport t) {
        assertThat(t.timeProfile().totalGoalsFor()).isEqualTo(t.total().goalsFor());
        assertThat(t.timeProfile().totalGoalsAgainst()).isEqualTo(t.total().goalsAgainst());
    }

    @Test
    void foulsByBucketAndScoreSituation() {
        TeamReport velez = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), TODAY), "CAVS");
        TimeProfile tp = velez.timeProfile();

        // Vélez 2-4 Argentinos (26390): 12 faltas propias y 7 rivales; Vélez 7-0 Comunicaciones (26396): 4 y 5.
        assertThat(tp.buckets()).extracting(TimeBucket::foulsOwn).containsExactly(0, 1, 1, 1, 1, 4, 1, 2, 3, 2);
        assertThat(tp.buckets()).extracting(TimeBucket::foulsRival).containsExactly(0, 1, 1, 2, 0, 3, 2, 1, 0, 2);
        assertThat(velez.fouls().own()).isEqualTo(16);
        assertThat(velez.fouls().opponent()).isEqualTo(12);
        assertThat(velez.fouls().ownPerGame()).isEqualTo(8.0);
        assertThat(velez.fouls().rivalPerGame()).isEqualTo(6.0);

        // Cómo iba al empezar cada tramo (26390: 0,-1,-1,-1,-2,-2,-2,-2,-2,-3; 26396: 0,0,1,1,1,1,3,4,6,7).
        assertThat(tp.buckets()).extracting(TimeBucket::avgGoalDiff)
                .containsExactly(0.0, -0.5, 0.0, 0.0, -0.5, -0.5, 0.5, 1.0, 2.0, 2.0);
        TimeBucket start = tp.buckets().getFirst();
        assertThat(start.level()).isEqualTo(2);
        TimeBucket t2_20 = tp.buckets().getLast();
        assertThat(t2_20.ahead()).isEqualTo(1);
        assertThat(t2_20.behind()).isEqualTo(1);
        // byPeriod: cómo iba al empezar cada tiempo.
        assertThat(tp.byPeriod()).extracting(TimeBucket::avgGoalDiff).containsExactly(0.0, -0.5);
    }

    @Test
    void foulsByScore() {
        ReportData.FoulSituation s = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), TODAY),
                "CAVS").timeProfile().foulSituation();

        // Vélez 2-4 Argentinos y 7-0 Comunicaciones: 16 faltas propias, 12 perdiendo y 4 ganando (calculado aparte).
        assertThat(s).isEqualTo(new ReportData.FoulSituation(4, 0, 12, 2411, 869, 2720));
        assertThat(s.secondsAhead() + s.secondsLevel() + s.secondsBehind()).isEqualTo(2 * 50 * 60);
        assertThat(s.per10Behind()).isCloseTo(12 * 600.0 / 2720, within(1e-9));
        assertThat(s.per10Level()).isZero();
    }

    @Test
    void shortHandedGoalsMatchSanctionMinutes() {
        ReportData.TimeProfile tp = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), TODAY),
                "CAVS").timeProfile();
        ReportData.ShortHanded sh = tp.shortHanded();
        // Los segundos de sanción por partido suman lo mismo que el total con uno menos del informe.
        assertThat(sh.seconds()).isEqualTo(tp.shortHandedSeconds());
        assertThat(sh.matches()).allSatisfy(m -> assertThat(m.secondsShort()).isPositive());
    }

    @Test
    void leagueFoulAverageWithAllProfiles() {
        ReportData d = StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), TODAY);

        // Fichas cargadas en el test: 26390 (19 faltas), 26396 (9) y 26389 (14) = 42 faltas en 3 partidos.
        assertThat(d.league().matchesWithSheet()).isEqualTo(3);
        assertThat(d.league().foulsPerTeamGame()).isEqualTo(42 / 6.0);
        assertThat(team(d, "CAVS").fouls().leaguePerTeamGame()).isEqualTo(7.0);
        assertThat(team(d, "CAVS").indicators()).anySatisfy(i -> {
            assertThat(i.metric()).isEqualTo("Faltas por partido");
            assertThat(i.value()).isEqualTo(8.0);
        });
    }

    @Test
    void withoutPublishedSheetsThereIsNoMinuteProfile() {
        TimeProfile tp = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), TODAY), "CAVS A")
                .timeProfile();

        assertThat(tp.hasData()).isFalse();
        assertThat(tp.matchesPlayed()).isEqualTo(9);
        assertThat(tp.periodMinutes()).isEqualTo(20);
        assertThat(tp.buckets()).hasSize(8);
    }

    @Test
    void splitsTheClubTeamsAAndB() {
        ReportData report = StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), TODAY);

        assertThat(report.teams()).extracting(TeamReport::code).containsExactly("CAVS A", "CAVS B");
        // Valores calculados por separado (script Python sobre el mismo HTML).
        assertThat(team(report, "CAVS A").total()).isEqualTo(new TeamRecord(9, 8, 0, 1, 80, 8));
        assertThat(team(report, "CAVS B").total()).isEqualTo(new TeamRecord(10, 3, 0, 7, 29, 74));
    }

    @Test
    void goalkeepersAreNotAmongScorers() {
        CompetitionData full = data(765);
        TeamReport without = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(full), TODAY), "CAVS A");
        ReportData.PlayerLine goalkeeperWoman = without.players().getLast();
        CompetitionData withGoalkeeperWoman = new CompetitionData(full.competition(), full.standings(), full.standingsDate(),
                full.matches(), full.players(), full.sheets(), java.util.Set.of(goalkeeperWoman.playerId()));

        TeamReport with = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(withGoalkeeperWoman), TODAY), "CAVS A");

        assertThat(with.players()).hasSize(without.players().size() - 1)
                .noneMatch(p -> p.playerId() == goalkeeperWoman.playerId());
        // Sus tarjetas y su pelota parada siguen contando para el equipo.
        assertThat(with.discipline()).isEqualTo(without.discipline());
        assertThat(with.setPieces()).isEqualTo(without.setPieces());
    }

    @Test
    void profileStandingsAndPlayers() {
        TeamReport a = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), TODAY), "CAVS A");

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
    void upcomingMatchesOnlyFromToday() {
        TeamReport velez = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), TODAY), "CAVS");

        assertThat(velez.results()).extracting(ReportData.MatchLine::opponent, ReportData.MatchLine::score,
                        ReportData.MatchLine::outcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ARGENTINOS JUNIORS", "2 - 4", "P"),
                        org.assertj.core.groups.Tuple.tuple("COMUNICACIONES", "7 - 0", "G"));
        // Jornadas 3 a 5 todavía sin fecha asignada.
        assertThat(velez.upcoming()).hasSize(3)
                .allSatisfy(m -> assertThat(m.date()).isNull())
                .extracting(ReportData.MatchLine::opponent).containsExactly("HURACAN", "RIVER PLATE", "GEBA B");
        assertThat(velez.upcoming().getFirst().home()).isFalse();
    }

    @Test
    void leagueContextUsesAllPlayedMatches() {
        ReportData report = StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), TODAY);

        // 8 partidos jugados: 1-3, 1-7, 13-2, 2-4 ... promedio por equipo y partido = goles / 16
        assertThat(report.league().matchesPlayed()).isEqualTo(8);
        assertThat(report.league().goalsPerTeamGame()).isPositive();
        assertThat(report.sources()).singleElement()
                .satisfies(s -> assertThat(s.url()).endsWith("/league/779"));
    }

    @Test
    void seasonAddsOpeningAndClosingAndFlagsOutdatedTable() {
        // La tabla del Apertura (693) quedó congelada el 03/05/2026 con 10 PJ; el calendario tiene todos los partidos.
        TeamReport a = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Temporada",
                List.of(data(693), data(765)), TODAY), "CAVS A");

        assertThat(a.byTournament()).extracting(ReportData.TournamentLine::tournament).containsExactly("Apertura", "Clausura");
        assertThat(a.total().played())
                .isEqualTo(a.byTournament().get(0).record().played() + a.byTournament().get(1).record().played());
        assertThat(a.standings()).hasSize(2);
        ReportData.StandingSnapshot apertura = a.standings().getFirst();
        assertThat(apertura.publishedOn()).isEqualTo(LocalDate.of(2026, 5, 3));
        assertThat(apertura.played()).isLessThan(a.byTournament().getFirst().record().played());
        assertThat(apertura.outdated()).isTrue();
        assertThat(a.standings().get(1).outdated()).isFalse();
    }

    @Test
    void theReportCanBeOfASingleTeam() {
        ReportData onlyB = StatsEngine.build(VELEZ, "Infantil B Femenino", "Clausura", List.of(data(765)), TODAY, 0, "CAVS B");

        assertThat(onlyB.teams()).extracting(TeamReport::code).containsExactly("CAVS B");
        assertThat(onlyB.teams().getFirst().name()).isEqualTo("VELEZ B");
        assertThat(onlyB.teams().getFirst().total()).isEqualTo(new TeamRecord(10, 3, 0, 7, 29, 74));
        // El cruce con Vélez A sigue apareciendo como rival.
        assertThat(onlyB.teams().getFirst().rivals()).anySatisfy(r -> assertThat(r.opponent()).isEqualTo("VELEZ A"));
    }

    @Test
    void crossMatchBetweenAAndBCountsForBoth() {
        ReportData report = StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), TODAY);

        assertThat(team(report, "CAVS A").rivals()).anySatisfy(r -> assertThat(r.opponent()).isEqualTo("VELEZ B"));
        assertThat(team(report, "CAVS B").rivals()).anySatisfy(r -> assertThat(r.opponent()).isEqualTo("VELEZ A"));
    }

    public static TeamReport team(ReportData r, String code) {
        return r.teams().stream().filter(t -> t.code().equals(code)).findFirst().orElseThrow();
    }

    /** La misma competición como copa interprovincial (Copa Nacional). */
    static CompetitionData asCup(CompetitionData cd) {
        Competition c = cd.competition();
        return new CompetitionData(Competition.cup(c.id(), "COPA NACIONAL DE CLUBES", c.federationId(), c.seasonId(),
                c.category(), c.teams(), c.periodMinutes()), cd.standings(), cd.standingsDate(), cd.matches(),
                cd.players(), cd.sheets());
    }

    /** Las copas suman al equipo, pero la referencia de la liga es la de los torneos locales. */
    @Test
    void theLeagueReferenceLeavesTheCupsOut() {
        ReportData local = StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), TODAY);
        ReportData withCup = StatsEngine.build(VELEZ, "Primera Masculino", "Clausura",
                List.of(data(779), asCup(data(765))), TODAY);
        assertThat(withCup.league()).isEqualTo(local.league());
        assertThat(withCup.sources()).hasSize(2);
        // Solo copas: la referencia es la de la copa.
        ReportData onlyCup = StatsEngine.build(VELEZ, "Primera Masculino", "Copa", List.of(asCup(data(779))), TODAY);
        assertThat(onlyCup.league().matchesPlayed()).isEqualTo(local.league().matchesPlayed());
    }

    /** Sin libres ni penales en toda la liga (gestiondepsj no los distingue), la pelota parada no se evalúa. */
    @Test
    void withoutSetPiecesInTheLeagueTheyAreNotEvaluated() {
        ReportData sidgad = StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), TODAY);
        assertThat(sidgad.hasSetPieces()).isTrue();
        assertThat(sidgad.teams().getFirst().indicators()).anyMatch(x -> x.area().equals("Pelota parada"));

        CompetitionData cd = data(779);
        List<Models.PlayerStats> plain = cd.players().stream().map(p -> new Models.PlayerStats(p.playerId(),
                p.teamId(), p.teamCode(), p.clubId(), p.name(), p.goals(), p.games(), 0, 0, 0, 0, 0, p.blueCards(),
                p.redCards())).toList();
        CompetitionData psj = new CompetitionData(cd.competition(), cd.standings(), cd.standingsDate(), cd.matches(),
                plain, cd.sheets());
        ReportData d = StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(psj), TODAY);
        assertThat(d.hasSetPieces()).isFalse();
        assertThat(d.teams().getFirst().indicators()).noneMatch(x -> x.area().equals("Pelota parada"));
    }
}
