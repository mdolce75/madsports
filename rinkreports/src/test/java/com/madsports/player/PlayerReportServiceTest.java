package com.madsports.player;

import com.madsports.catalog.RosterService;
import com.madsports.Fixtures;
import com.madsports.player.PlayerReport.Line;
import com.madsports.player.PlayerReportService.Query;
import com.madsports.player.PlayerReportService.TeamInput;
import com.madsports.report.ReportService.Scope;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.SeasonMatch;
import com.madsports.sidgad.parse.SidgadParser;
import com.madsports.stats.ReportData;
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
    static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    final Competition primera = SidgadParser.catalog(Fixtures.read("catalogo.html")).stream()
            .filter(c -> c.id() == 779).findFirst().orElseThrow();
    final List<Match> calendar = SidgadParser.calendar(Fixtures.read("cal_779.html"));

    /** Partidos jugados del equipo en el calendario, con la planilla de su lado en los que hay acta en los fixtures. */
    TeamInput team(int teamId, String category, Map<Integer, String> sheets) {
        List<Match> matches = PlayerReportService.teamMatches(calendar, teamId);
        Map<Integer, List<LineupPlayer>> lineups = new LinkedHashMap<>();
        Map<Integer, List<MatchEvent>> events = new LinkedHashMap<>();
        for (Match m : matches) {
            String html = sheets.getOrDefault(m.idp(), "File not found.");
            lineups.put(m.idp(), RosterService.ownSide(SidgadParser.lineups(html), m, Set.of(teamId)));
            events.put(m.idp(), SidgadParser.gameEvents(html));
        }
        return new TeamInput(primera, category, teamId, matches, lineups, events);
    }

    Map<Integer, String> velezSheets() {
        return Map.of(26390, Fixtures.read("gr_26390.html"), 26396, Fixtures.read("gr_26396.html"));
    }

    static Query query(int playerId, String name) {
        return new Query(playerId, name, "Usuario De Prueba", 74, 2026, Scope.CLOSING);
    }

    @Test
    void matchesGoalsAndCardsFromTheSheets() {
        PlayerReport r = PlayerReportService.assemble(query(3851, "Ignacio J Graiño"), VELEZ, TODAY, LEVELS,
                List.of(team(5581, "Primera Masculino", velezSheets())));

        // Vélez 2-4 Argentinos (2 goles de Graiño) y 7-0 Comunicaciones (2 goles).
        assertThat(r.categories()).containsExactly(new Line("Primera Masculino", 2, 1, 0, 1, 4, 0, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0));
        assertThat(r.total()).isEqualTo(new Line("Total", 2, 1, 0, 1, 4, 0, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0));
        assertThat(r.total().goalsPerGame()).isEqualTo(2.0);
        // Incidencia: sus 4 goles sobre los 9 de Vélez en esos partidos (2 + 7).
        assertThat(r.total().teamGoals()).isEqualTo(9);
        assertThat(r.total().goalShare()).isCloseTo(44.44, org.assertj.core.data.Offset.offset(0.01));
        assertThat(r.matches()).extracting(PlayerReport.MatchRow::opponent)
                .containsExactly("ARGENTINOS JUNIORS", "COMUNICACIONES");
        assertThat(r.matches()).extracting(PlayerReport.MatchRow::result).containsExactly("P", "G");
        assertThat(r.notes()).isEmpty();
    }

    @Test
    void awayCardsAndMatchesWithoutSheet() {
        // Argentinos (visitante en el 26390) jugó otro partido cuya acta no está en los fixtures: queda sin contar.
        int argentinos = calendar.stream().filter(m -> m.idp() == 26390).findFirst().orElseThrow().awayTeamId();
        PlayerReport r = PlayerReportService.assemble(query(2698, "Nicolas Ortin"), VELEZ, TODAY, LEVELS,
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

    /** Argentinos con el 26390 (acta en los fixtures) y otro partido jugado sin acta publicada. */
    TeamInput argentinosWithMatchWithoutSheet() {
        int argentinos = calendar.stream().filter(m -> m.idp() == 26390).findFirst().orElseThrow().awayTeamId();
        TeamInput withSheet = team(argentinos, "Primera Masculino", velezSheets());
        Match withoutSheet = new Match(99001, 3, LocalDate.of(2026, 10, 17), null, "", argentinos, 0, "ARGENTINOS JUNIORS",
                1, 0, "RIVAL", 5, 1);
        List<Match> matches = new java.util.ArrayList<>(withSheet.matches());
        matches.add(withoutSheet);
        Map<Integer, List<LineupPlayer>> sheets = new LinkedHashMap<>(withSheet.sheets());
        sheets.put(withoutSheet.idp(), List.of());
        return new TeamInput(primera, "Primera Masculino", argentinos, matches, sheets, withSheet.events());
    }

    @Test
    void matchesWithoutSheetFromTheSeasonProfile() {
        TeamInput base = argentinosWithMatchWithoutSheet();
        Match other = base.matches().getLast();
        // En su ficha de temporada figura en el partido sin acta: 2 goles, 1 azul, 1/1 libre y 0/1 penal.
        TeamInput in = base.withSummaries(PlayerReportService.summaries(base, List.of(
                new SeasonMatch(primera.name(), other.date(), "", "", 0, 0, other.idp(), 2, 0, 1, 1, 0, 1, 1, 0))));

        PlayerReport r = PlayerReportService.assemble(query(2698, "Nicolas Ortin"), VELEZ, TODAY, LEVELS, List.of(in));

        Line l = r.categories().getFirst();
        assertThat(l.played()).isEqualTo(2);
        assertThat(l.withoutSheet()).isZero();     // los demás sin acta no figuran en su ficha: no los jugó
        assertThat(l.goals()).isEqualTo(2);
        assertThat(l.yellowCards()).isEqualTo(1);  // la amarilla sale del acta del 26390
        assertThat(l.blueCards()).isEqualTo(1);
        assertThat(l.directFreeScored()).isEqualTo(1);
        assertThat(l.penaltiesTaken()).isEqualTo(1);
        assertThat(r.matches()).filteredOn(PlayerReport.MatchRow::fromSummary).singleElement()
                .extracting(PlayerReport.MatchRow::goals).isEqualTo(2);
        // Sin minuto: sus goles y la azul van a "sin minuto registrado".
        assertThat(r.minutes().unrecorded().goals()).isEqualTo(2);
        assertThat(r.minutes().unrecorded().blueCards()).isEqualTo(1);
        // El total de los tramos cuenta también los sin minuto: es el mismo de los totales del informe.
        PlayerReport.Bucket total = r.minutes().total();
        assertThat(total.goals()).isEqualTo(r.total().goals());
        assertThat(total.yellowCards()).isEqualTo(r.total().yellowCards());
        assertThat(total.blueCards()).isEqualTo(r.total().blueCards());
        assertThat(total.redCards()).isEqualTo(r.total().redCards());
        // Incidencia de tarjetas: solo los partidos con acta (de los sin acta no se conocen las del equipo). La amarilla
        // del 26390 es la única de Argentinos en esa acta; la azul del partido sin acta no entra.
        assertThat(l.teamCards()).isEqualTo(1);
        assertThat(l.sheetCards()).isEqualTo(1);
        assertThat(l.cardShare()).isEqualTo(100.0);
        assertThat(r.notes()).singleElement().asString().contains("1 partido sin acta publicada se tomó",
                "ficha de temporada");
    }

    @Test
    void withoutIdpItSearchesByDateInTheSameCompetition() {
        TeamInput base = argentinosWithMatchWithoutSheet();
        Match other = base.matches().getLast();

        assertThat(PlayerReportService.summaries(base, List.of(
                new SeasonMatch(primera.name(), other.date(), "", "", 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0))))
                .containsOnlyKeys(other.idp());
        assertThat(PlayerReportService.summaries(base, List.of(
                new SeasonMatch("TORNEO CLAUSURA JUNIORS MASCULINO (FPP)", other.date(), "", "", 0, 0, 0, 1, 0, 0, 0, 0,
                        0, 0, 0)))).isEmpty();
        // El partido con acta nunca se toma de la ficha.
        assertThat(PlayerReportService.summaries(base, List.of(
                new SeasonMatch(primera.name(), null, "", "", 0, 0, 26390, 1, 0, 0, 0, 0, 0, 0, 0)))).isEmpty();
    }

    @Test
    void categoriesAreSortedByLevelAndAddedToTheTotal() {
        PlayerReport r = PlayerReportService.assemble(query(3851, "Ignacio J Graiño"), VELEZ, TODAY, LEVELS, List.of(
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
    void goalsByFiveMinuteBucket() {
        PlayerReport r = PlayerReportService.assemble(query(3851, "Ignacio J Graiño"), VELEZ, TODAY, LEVELS,
                List.of(team(5581, "Primera Masculino", velezSheets())));
        PlayerReport.Minutes mn = r.minutes();

        // Primera: tiempos de 25' -> 5 tramos por tiempo.
        assertThat(mn.periodMinutes()).isEqualTo(25);
        assertThat(mn.buckets()).hasSize(10);
        assertThat(mn.buckets().getFirst().label()).isEqualTo("1T 0-5'");
        assertThat(mn.buckets().getLast().label()).isEqualTo("2T 20-25'");
        // Los 4 goles de la planilla están en algún tramo o como "sin minuto".
        int withMinute = mn.buckets().stream().mapToInt(PlayerReport.Bucket::goals).sum();
        assertThat(withMinute + mn.unrecorded().goals()).isEqualTo(4);
        assertThat(mn.total().goals()).isEqualTo(4).isEqualTo(r.total().goals());
        assertThat(mn.byHalf().stream().mapToInt(PlayerReport.Bucket::goals).sum()).isEqualTo(withMinute);
        assertThat(mn.hasData()).isTrue();
    }

    @Test
    void cardsByColorAndBucket() {
        PlayerReportService.MinuteCounter counter = new PlayerReportService.MinuteCounter(20);
        LineupPlayer sheet = new LineupPlayer(7, "X", true, "7", "Jugador", true, 1, 1, 2, 1);
        counter.add(sheet, List.of(
                new MatchEvent(1, 3, 0, EventType.GOAL, 0, 0, "", "", 7),
                new MatchEvent(1, 19, 59, EventType.YELLOW_CARD, 0, 0, "", "", 7),
                new MatchEvent(10, 0, 0, EventType.BLUE_CARD, 0, 0, "", "", 7),   // descanso: inicio del 2T
                new MatchEvent(2, 7, 0, EventType.BLUE_CARD, 0, 0, "", "", 7),
                new MatchEvent(11, 0, 0, EventType.RED_CARD, 0, 0, "", "", 7)));  // fuera del juego
        PlayerReport.Minutes mn = counter.result();

        assertThat(mn.buckets()).hasSize(8);
        assertThat(mn.buckets().get(0)).isEqualTo(new PlayerReport.Bucket("1T 0-5'", 1, 0, 0, 0));
        assertThat(mn.buckets().get(3)).isEqualTo(new PlayerReport.Bucket("1T 15-20'", 0, 1, 0, 0));
        assertThat(mn.buckets().get(4)).isEqualTo(new PlayerReport.Bucket("2T 0-5'", 0, 0, 1, 0));
        assertThat(mn.buckets().get(5)).isEqualTo(new PlayerReport.Bucket("2T 5-10'", 0, 0, 1, 0));
        assertThat(mn.unrecorded()).isEqualTo(new PlayerReport.Bucket("Sin minuto registrado", 0, 0, 0, 1));
    }

    @Test
    void penaltiesAndDirectFreeHits() {
        // Pérez Barbosa (Vélez) erró un penal en el 26390 (0/1).
        PlayerReport r = PlayerReportService.assemble(query(5974, "Juan Ignacio Perez Barbosa"), VELEZ, TODAY, LEVELS,
                List.of(team(5581, "Primera Masculino", velezSheets())));
        Line t = r.total();
        assertThat(t.penaltiesScored()).isZero();
        assertThat(t.penaltiesTaken()).isEqualTo(1);
        assertThat(t.penaltyRate()).isZero();
        assertThat(t.hasSetPieces()).isTrue();

        // Costa (Huracán) convirtió un libre directo contra GEBA B (1/1).
        List<LineupPlayer> huracan = SidgadParser.lineups(Fixtures.read("gr_26389.html")).stream()
                .filter(LineupPlayer::home).toList();
        assertThat(huracan).filteredOn(p -> p.playerId() == 5973).singleElement().satisfies(p -> {
            assertThat(p.directFreeScored()).isEqualTo(1);
            assertThat(p.directFreeTaken()).isEqualTo(1);
        });
    }

    @Test
    void playableCategories() {
        assertThat(Eligibility.levels("INFANTIL")).containsExactly("INFANTIL", "PREJUVENIL", "JUVENIL");
        assertThat(Eligibility.levels("PREJUVENIL")).containsExactly("PREJUVENIL", "JUVENIL", "JUNIORS");
        assertThat(Eligibility.levels("JUVENIL")).containsExactly("JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");
        assertThat(Eligibility.levels("juniors")).containsExactly("JUNIORS", "RESERVA", "PRIMERA");
        assertThat(Eligibility.levels("RESERVA")).containsExactly("RESERVA", "PRIMERA");
        assertThat(Eligibility.levels("PRIMERA")).containsExactly("RESERVA", "PRIMERA");
        assertThat(Eligibility.levels(null)).isEmpty();
    }

    @Test
    void onlyEnabledCategoriesOfTheirBranchAreChecked() {
        Query juvenil = new Query(1, "X", "Y", 74, 2026, Scope.SEASON, Eligibility.levels("JUVENIL"), "MASCULINO");
        assertThat(juvenil.eligible(primera)).isTrue();     // Primera Masculino
        Query infantil = new Query(1, "X", "Y", 74, 2026, Scope.SEASON, Eligibility.levels("INFANTIL"), "MASCULINO");
        assertThat(infantil.eligible(primera)).isFalse();
        Query female = new Query(1, "X", "Y", 74, 2026, Scope.SEASON, Eligibility.levels("PRIMERA"), "FEMENINO");
        assertThat(female.eligible(primera)).isFalse();
        assertThat(query(1, "X").eligible(primera)).isTrue();   // sin categoría guardada: todas

        assertThat(juvenil.eligibleLabel()).isEqualTo("Juvenil, Juniors, Reserva y Primera Masculino");
        PlayerReport r = PlayerReportService.assemble(juvenil, VELEZ, TODAY, LEVELS, List.of());
        assertThat(r.notes().getFirst()).isEqualTo(
                "Categorías revisadas: Juvenil, Juniors, Reserva y Primera Masculino, las que corresponden a su categoría.");
    }

    @Test
    void goalsConcededDuringSanction() {
        // Bueno (GEBA B, visitante) recibió una azul en el 13-2 de Huracán (26389).
        int gebaB = calendar.stream().filter(m -> m.idp() == 26389).findFirst().orElseThrow().awayTeamId();
        PlayerReport r = PlayerReportService.assemble(query(3899, "Nicolas Bueno"), VELEZ, TODAY, LEVELS,
                List.of(team(gebaB, "Primera Masculino", Map.of(26389, Fixtures.read("gr_26389.html")))));

        ReportData.ShortHanded sh = r.shortHanded();
        assertThat(sh.matches()).singleElement().satisfies(m -> {
            assertThat(m.opponent()).isEqualTo("HURACAN");
            assertThat(m.sanctions()).isEqualTo("1 azul");
            assertThat(m.secondsShort()).isEqualTo(120);
            assertThat(m.finalFor()).isEqualTo(2);
            assertThat(m.finalAgainst()).isEqualTo(13);
            assertThat(m.result()).isEqualTo("P");
        });
        // Todos los goles de Huracán caen dentro o fuera de su sanción.
        assertThat(sh.goalsAgainst() + sh.otherGoalsAgainst()).isEqualTo(13);
        // El detalle enumera cada gol en contra durante su azul, con el minuto de la tarjeta y el del gol.
        assertThat(sh.conceded()).hasSize(sh.goalsAgainst()).allSatisfy(g -> {
            assertThat(g.opponent()).isEqualTo("HURACAN");
            assertThat(g.card()).isEqualTo("Azul");
            assertThat(g.cardMinute()).matches("[12]T \\d{1,2}:\\d{2}");
            assertThat(g.goalMinute()).matches("[12]T \\d{1,2}:\\d{2}");
            assertThat(g.result()).isEqualTo("P");
        });
        // Graiño no tuvo sanciones.
        assertThat(PlayerReportService.assemble(query(3851, "Ignacio J Graiño"), VELEZ, TODAY, LEVELS,
                List.of(team(5581, "Primera Masculino", velezSheets()))).shortHanded().hasData()).isFalse();
    }

    @Test
    void strengthsAgainstTeammatesInTheSameMatches() {
        PlayerReport r = PlayerReportService.assemble(query(3851, "Ignacio J Graiño"), VELEZ, TODAY, LEVELS,
                List.of(team(5581, "Primera Masculino", velezSheets())));
        Map<String, ReportData.Indicator> byMetric = new LinkedHashMap<>();
        r.indicators().forEach(i -> byMetric.put(i.metric(), i));

        // 2-4 Argentinos (2 goles suyos, 0 de los 8 compañeros) y 7-0 Comunicaciones (2 suyos, 5 de los compañeros).
        ReportData.Indicator goals = byMetric.get("Goles por partido");
        assertThat(goals.value()).isEqualTo(2.0);
        assertThat(goals.referenceLabel()).isEqualTo("Compañeros");
        assertThat(goals.reference()).isEqualTo(5.0 / 16);
        // Titular en los dos; de los compañeros, 4 de 8 en cada partido.
        assertThat(byMetric.get("Partidos en el cinco inicial").value()).isEqualTo(100.0);
        assertThat(byMetric.get("Partidos en el cinco inicial").reference()).isEqualTo(50.0);
        // Con 2 partidos la muestra es chica: no se califica.
        assertThat(r.indicators()).allMatch(i -> i.verdict() == ReportData.Verdict.SMALL_SAMPLE);
        assertThat(byMetric).containsKeys("Tarjetas por partido", "Azules y rojas por partido", "Penales convertidos",
                "Libres directos convertidos", "Efectividad del equipo con él");
    }

    @Test
    void teamEffectiveness() {
        assertThat(PlayerStrengths.effectiveness(new int[]{2, 1, 0})).isEqualTo(700.0 / 9);
        assertThat(PlayerStrengths.effectiveness(new int[]{0, 0, 0})).isZero();
    }

    @Test
    void whereToSearchByTheCategoryTheyAppearIn() {
        // Quien figura en Primera puede ser Juvenil, Juniors, Reserva o Primera: se busca en todas esas.
        assertThat(Eligibility.possibleBases("PRIMERA")).containsExactly("JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");
        assertThat(Eligibility.searchLevels("PRIMERA")).containsExactly("JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");
        assertThat(Eligibility.searchLevels("JUNIORS"))
                .containsExactly("PREJUVENIL", "JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");
        assertThat(Eligibility.searchLevels("INFANTIL")).containsExactly("INFANTIL", "PREJUVENIL", "JUVENIL");
        assertThat(Eligibility.searchLevels(null)).isEmpty();
        assertThat(Eligibility.lowest(List.of("PRIMERA", "JUVENIL", "JUNIORS"))).isEqualTo("JUVENIL");
    }

    @Test
    void theirCategoryIsTheLowestTheyPlayed() {
        // Graiño figura en Primera y (simulado) en un equipo de Juvenil: es Juvenil.
        Competition juvenil = new Competition(primera.id(), primera.name(), primera.federationId(), primera.seasonId(),
                primera.tournament(), new Competition.Category("JUVENIL", "MASCULINO"), 1, primera.teams(),
                primera.periodMinutes());
        TeamInput inPrimera = team(5581, "Primera Masculino", velezSheets());
        TeamInput inJuvenil = new TeamInput(juvenil, "Juvenil Masculino", 5581, inPrimera.matches(), inPrimera.sheets(),
                inPrimera.events());
        assertThat(PlayerReportService.baseLevel(3851, List.of(inPrimera, inJuvenil))).contains("JUVENIL");
        assertThat(PlayerReportService.baseLevel(3851, List.of(inPrimera))).contains("PRIMERA");
        assertThat(PlayerReportService.baseLevel(1, List.of(inPrimera))).isEmpty();

        // Con su categoría real se revisan las de un Juvenil (incluye Juvenil y Juniors), y el informe lo aclara.
        Query q = new Query(3851, "Ignacio J Graiño", "X", 74, 2026, Scope.SEASON,
                Eligibility.searchLevels("PRIMERA"), "MASCULINO").withBase("JUVENIL");
        assertThat(q.levels()).containsExactly("JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");
        assertThat(q.eligible(juvenil)).isTrue();
        PlayerReport r = PlayerReportService.assemble(q, VELEZ, TODAY, LEVELS, List.of(inJuvenil, inPrimera));
        assertThat(r.notes().getFirst()).isEqualTo("Su categoría es Juvenil (la más baja en que jugó en 2026). "
                + "Categorías en que puede jugar: Juvenil, Juniors, Reserva y Primera Masculino.");
        assertThat(r.categories()).extracting(Line::category).containsExactly("Juvenil Masculino", "Primera Masculino");
    }

    @Test
    void minuteWithinEachPeriod() {
        assertThat(PlayerReportService.clock(600, 25)).isEqualTo("1T 10:00");
        assertThat(PlayerReportService.clock(1500, 25)).isEqualTo("2T 0:00");
        assertThat(PlayerReportService.clock(1630, 25)).isEqualTo("2T 2:10");
        assertThat(PlayerReportService.clock(1250, 20)).isEqualTo("2T 0:50");
    }

    @Test
    void playableCategoriesInAmp() {
        Federation amp = Federation.AMP;
        // Misma regla que en la FPP, con las categorías de Mendoza: la suya y dos arriba; desde Juvenil, hasta Senior.
        assertThat(Eligibility.levels(amp, "INFANTIL")).containsExactly("INFANTIL", "PRECADETE", "CADETE");
        assertThat(Eligibility.levels(amp, "CADETE")).containsExactly("CADETE", "PREJUVENIL", "JUVENIL");
        assertThat(Eligibility.levels(amp, "JUVENIL")).containsExactly("JUVENIL", "PREJUNIOR", "JUNIOR", "RESERVA", "SENIOR");
        assertThat(Eligibility.levels(amp, "SENIOR")).containsExactly("RESERVA", "SENIOR");
        assertThat(Eligibility.levels(amp, "JUNIORS")).isEmpty();   // categoría de la FPP
        // Quien figura en Senior puede ser de Juvenil para arriba.
        assertThat(Eligibility.searchLevels(amp, "SENIOR"))
                .containsExactly("JUVENIL", "PREJUNIOR", "JUNIOR", "RESERVA", "SENIOR");
        assertThat(Eligibility.lowest(amp, List.of("SENIOR", "PREJUNIOR", "JUVENIL"))).isEqualTo("JUVENIL");
    }

    @Test
    void ifNotFoundItSaysSo() {
        PlayerReport r = PlayerReportService.assemble(query(1, "Nadie"), VELEZ, TODAY, LEVELS,
                List.of(team(5581, "Primera Masculino", velezSheets())));
        assertThat(r.hasData()).isFalse();
        assertThat(r.categories()).isEmpty();
        assertThat(r.notes()).singleElement().asString().contains("Nadie no figura", "VELEZ", "2026");
    }

    @Test
    void theSquadComesFromTheTeamLineups() {
        Match m = calendar.stream().filter(x -> x.idp() == 26390).findFirst().orElseThrow();
        List<RosterService.RosterPlayer> roster = RosterService.roster(
                RosterService.ownSide(SidgadParser.lineups(Fixtures.read("gr_26390.html")), m, Set.of(5581)),
                "Primera Masculino");
        // Los 9 citados de Vélez (la tabla de estadísticas solo lista a los goleadores).
        assertThat(roster).hasSize(9).extracting(RosterService.RosterPlayer::id).contains(3851, 4156, 5888);
        assertThat(roster).extracting(RosterService.RosterPlayer::name).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        // Nombre y apellido separados como en el acta (apellido compuesto incluido).
        assertThat(roster).filteredOn(p -> p.id() == 5974).singleElement().satisfies(p -> {
            assertThat(p.firstName()).isEqualTo("Juan Ignacio");
            assertThat(p.lastName()).isEqualTo("Perez Barbosa");
        });
    }

    @Test
    void fileName() {
        assertThat(PlayerReportService.slug("Ignacio J Graiño")).isEqualTo("ignacio-j-graino");
    }
}
