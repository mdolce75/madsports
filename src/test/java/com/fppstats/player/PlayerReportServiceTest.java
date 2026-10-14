package com.fppstats.player;

import com.fppstats.Fixtures;
import com.fppstats.player.PlayerReport.Line;
import com.fppstats.player.PlayerReportService.Query;
import com.fppstats.player.PlayerReportService.TeamInput;
import com.fppstats.report.ReportService.Scope;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Models.LineupPlayer;
import com.fppstats.sidgad.model.Models.EventType;
import com.fppstats.sidgad.model.Models.Match;
import com.fppstats.sidgad.model.Models.MatchEvent;
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
        return new Query(playerId, name, "Usuario De Prueba", 74, 2026, Scope.CLAUSURA);
    }

    @Test
    void partidosGolesYTarjetasDesdeLasActas() {
        PlayerReport r = PlayerReportService.assemble(query(3851, "Ignacio J Graiño"), VELEZ, HOY, LEVELS,
                List.of(team(5581, "Primera Masculino", velezSheets())));

        // Vélez 2-4 Argentinos (2 goles de Graiño) y 7-0 Comunicaciones (2 goles).
        assertThat(r.categories()).containsExactly(new Line("Primera Masculino", 2, 1, 0, 1, 4, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(r.total()).isEqualTo(new Line("Total", 2, 1, 0, 1, 4, 0, 0, 0, 0, 0, 0, 0, 0));
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
    void golesPorTramoDeCincoMinutos() {
        PlayerReport r = PlayerReportService.assemble(query(3851, "Ignacio J Graiño"), VELEZ, HOY, LEVELS,
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
        assertThat(mn.byHalf().stream().mapToInt(PlayerReport.Bucket::goals).sum()).isEqualTo(withMinute);
        assertThat(mn.hasData()).isTrue();
    }

    @Test
    void tarjetasPorColorYTramo() {
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
    void penalesYLibresDirectos() {
        // Pérez Barbosa (Vélez) erró un penal en el 26390 (0/1).
        PlayerReport r = PlayerReportService.assemble(query(5974, "Juan Ignacio Perez Barbosa"), VELEZ, HOY, LEVELS,
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
    void categoriasEnQuePuedeJugar() {
        assertThat(Eligibility.levels("INFANTIL")).containsExactly("INFANTIL", "PREJUVENIL", "JUVENIL");
        assertThat(Eligibility.levels("PREJUVENIL")).containsExactly("PREJUVENIL", "JUVENIL", "JUNIORS");
        assertThat(Eligibility.levels("JUVENIL")).containsExactly("JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");
        assertThat(Eligibility.levels("juniors")).containsExactly("JUNIORS", "RESERVA", "PRIMERA");
        assertThat(Eligibility.levels("RESERVA")).containsExactly("RESERVA", "PRIMERA");
        assertThat(Eligibility.levels("PRIMERA")).containsExactly("RESERVA", "PRIMERA");
        assertThat(Eligibility.levels(null)).isEmpty();
    }

    @Test
    void soloSeRevisanLasCategoriasHabilitadasDeSuRama() {
        Query juvenil = new Query(1, "X", "Y", 74, 2026, Scope.TEMPORADA, Eligibility.levels("JUVENIL"), "MASCULINO");
        assertThat(juvenil.eligible(primera)).isTrue();     // Primera Masculino
        Query infantil = new Query(1, "X", "Y", 74, 2026, Scope.TEMPORADA, Eligibility.levels("INFANTIL"), "MASCULINO");
        assertThat(infantil.eligible(primera)).isFalse();
        Query femenino = new Query(1, "X", "Y", 74, 2026, Scope.TEMPORADA, Eligibility.levels("PRIMERA"), "FEMENINO");
        assertThat(femenino.eligible(primera)).isFalse();
        assertThat(query(1, "X").eligible(primera)).isTrue();   // sin categoría guardada: todas

        assertThat(juvenil.eligibleLabel()).isEqualTo("Juvenil, Juniors, Reserva y Primera Masculino");
        PlayerReport r = PlayerReportService.assemble(juvenil, VELEZ, HOY, LEVELS, List.of());
        assertThat(r.notes().getFirst()).isEqualTo(
                "Categorías revisadas: Juvenil, Juniors, Reserva y Primera Masculino, las que corresponden a su categoría.");
    }

    @Test
    void golesRecibidosMientrasCumpliaSuSancion() {
        // Bueno (GEBA B, visitante) recibió una azul en el 13-2 de Huracán (26389).
        int gebaB = calendar.stream().filter(m -> m.idp() == 26389).findFirst().orElseThrow().awayTeamId();
        PlayerReport r = PlayerReportService.assemble(query(3899, "Nicolas Bueno"), VELEZ, HOY, LEVELS,
                List.of(team(gebaB, "Primera Masculino", Map.of(26389, Fixtures.read("gr_26389.html")))));

        ReportData.ShortHanded sh = r.shortHanded();
        assertThat(sh.matches()).singleElement().satisfies(m -> {
            assertThat(m.rival()).isEqualTo("HURACAN");
            assertThat(m.sanctions()).isEqualTo("1 azul");
            assertThat(m.secondsShort()).isEqualTo(120);
            assertThat(m.finalFor()).isEqualTo(2);
            assertThat(m.finalAgainst()).isEqualTo(13);
            assertThat(m.result()).isEqualTo("P");
        });
        // Todos los goles de Huracán caen dentro o fuera de su sanción.
        assertThat(sh.goalsAgainst() + sh.otherGoalsAgainst()).isEqualTo(13);
        // Graiño no tuvo sanciones.
        assertThat(PlayerReportService.assemble(query(3851, "Ignacio J Graiño"), VELEZ, HOY, LEVELS,
                List.of(team(5581, "Primera Masculino", velezSheets()))).shortHanded().hasData()).isFalse();
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
        // Nombre y apellido separados como en el acta (apellido compuesto incluido).
        assertThat(roster).filteredOn(p -> p.id() == 5974).singleElement().satisfies(p -> {
            assertThat(p.firstName()).isEqualTo("Juan Ignacio");
            assertThat(p.lastName()).isEqualTo("Perez Barbosa");
        });
    }

    @Test
    void nombreDeArchivo() {
        assertThat(PlayerReportService.slug("Ignacio J Graiño")).isEqualTo("ignacio-j-graino");
    }
}
