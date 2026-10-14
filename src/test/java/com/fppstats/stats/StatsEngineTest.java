package com.fppstats.stats;

import com.fppstats.Fixtures;
import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Models;
import com.fppstats.sidgad.model.Models.Match;
import com.fppstats.sidgad.model.Models.MatchEvent;
import com.fppstats.sidgad.model.Models.TeamRef;
import com.fppstats.sidgad.parse.SidgadParser;
import com.fppstats.stats.ReportData.TeamRecord;
import com.fppstats.stats.ReportData.TeamReport;
import com.fppstats.stats.ReportData.TimeBucket;
import com.fppstats.stats.ReportData.TimeProfile;
import com.fppstats.stats.StatsEngine.CompetitionData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class StatsEngineTest {

    public static final ClubView VELEZ = new ClubView(74, "CAVS", "VELEZ", List.of());
    public static final LocalDate HOY = LocalDate.of(2026, 10, 13);

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
    void golesYTarjetasPorTramosDeCincoMinutos() {
        TimeProfile tp = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), HOY), "CAVS")
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
    void ultimosCincoPartidosRecortaRecordYResultadosPeroNoLosPromediosDelTorneo() {
        List<CompetitionData> season = List.of(data(693), data(765));
        TeamReport full = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Temporada", season, HOY), "CAVS A");
        ReportData lastFiveReport = StatsEngine.build(VELEZ, "Infantil Femenino", "Últimos 5", season, HOY, 5);
        TeamReport last5 = team(lastFiveReport, "CAVS A");

        assertThat(lastFiveReport.partial()).isTrue();
        assertThat(last5.total().played()).isEqualTo(5);
        assertThat(last5.results()).containsExactlyElementsOf(full.results().subList(full.results().size() - 5, full.results().size()));
        assertThat(last5.form()).isEqualTo(full.form());
        assertThat(last5.byTorneo()).extracting(ReportData.TorneoLine::torneo).containsExactly("Clausura");
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
    void conUltimosPartidosLasTarjetasSeCuentanEnLasFichasDeEsosPartidos() {
        ClubView geba = new ClubView(76, "GEBA", "GEBA", List.of());
        TeamReport gebaB = team(StatsEngine.build(geba, "Primera Masculino", "Últimos 5", List.of(data(779)), HOY, 5), "GEBA B");

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
                List.of(data(779)), HOY), "GEBA B").discipline();
        assertThat(season.fromSheets()).isFalse();
        assertThat(season.yellowKnown()).isFalse();
    }

    @Test
    void tiempoConUnoMenosYConUnoMasSegunLasFichas() {
        // Huracán 13-2 GEBA B (26389): 4 azules a GEBA B (1T 18:21, 2T 01:05, 14:18, 22:37), sin superposición.
        ClubView geba = new ClubView(76, "GEBA", "GEBA", List.of());
        TimeProfile gebaB = team(StatsEngine.build(geba, "Primera Masculino", "Clausura", List.of(data(779)), HOY), "GEBA B")
                .timeProfile();
        assertThat(gebaB.shortHandedSeconds()).isEqualTo(4 * 120);
        assertThat(gebaB.powerPlaySeconds()).isZero();
        assertThat(gebaB.shortHandedMinutesPerGame()).isEqualTo(8.0);

        ClubView huracan = new ClubView(22, "HURA", "HURACAN", List.of());
        TimeProfile hura = team(StatsEngine.build(huracan, "Primera Masculino", "Clausura", List.of(data(779)), HOY), "HURA")
                .timeProfile();
        assertThat(hura.shortHandedSeconds()).isZero();
        assertThat(hura.powerPlaySeconds()).isEqualTo(4 * 120);
        // Las amarillas no dejan con uno menos.
        assertThat(hura.byPeriod().stream().mapToInt(TimeBucket::yellowOwn).sum()).isEqualTo(1);
    }

    @Test
    void tarjetasEnElDescansoYDespuesDelPartido() {
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

        TeamReport velez = team(StatsEngine.build(VELEZ, "Primera Masculino", "Últimos 5", List.of(cd), HOY, 5), "CAVS");
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
    void sancionesSuperpuestasSeSumanYAlFinalDelPartidoSeCortan() {
        int match = 2 * 25 * 60;
        // Dos azules simultáneas: se acumulan, 4'.
        assertThat(StatsEngine.sanctionSeconds(List.of(new int[]{600, 720}, new int[]{600, 720}), match)).isEqualTo(240);
        // Superpuestas en parte: 10:00-12:00 y 11:00-13:00 = 2' + 2'.
        assertThat(StatsEngine.sanctionSeconds(List.of(new int[]{600, 720}, new int[]{660, 780}), match)).isEqualTo(240);
        // A 1' del final: solo cuenta ese minuto.
        assertThat(StatsEngine.sanctionSeconds(List.of(new int[]{match - 60, match + 60}), match)).isEqualTo(60);
        // Al final del 1T (24:30) la sanción sigue en el 2T: 2' completos.
        assertThat(StatsEngine.sanctionSeconds(List.of(new int[]{1470, 1590}), match)).isEqualTo(120);
        // Roja: 4'.
        assertThat(StatsEngine.sanctionSeconds(List.of(new int[]{0, StatsEngine.RED_CARD_SECONDS}), match)).isEqualTo(240);
        assertThat(StatsEngine.sanctionSeconds(List.of(), match)).isZero();
    }

    @Test
    void ultimosPartidosLimitaElPerfilPorMinuto() {
        TimeProfile tp = team(StatsEngine.build(VELEZ, "Primera Masculino", "Últimos 1", List.of(data(779)), HOY, 1), "CAVS")
                .timeProfile();

        // Solo el último partido: Vélez 7-0 Comunicaciones.
        assertThat(tp.matchesPlayed()).isEqualTo(1);
        assertThat(tp.byPeriod()).extracting(TimeBucket::goalsFor).containsExactly(1, 6);
        assertThat(tp.byPeriod()).extracting(TimeBucket::goalsAgainst).containsExactly(0, 0);
    }

    @Test
    void losGolesSinMinutoHacenQueLaTablaSumeIgualQueElRecord() {
        // Todas las fichas: nada sin minuto.
        TeamReport completo = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(data(779)), HOY), "CAVS");
        assertThat(completo.timeProfile().hasUnrecordedGoals()).isFalse();
        assertTotalsMatchRecord(completo);

        // Sin ninguna ficha: todo el resultado queda sin minuto.
        TeamReport sinFichas = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), HOY), "CAVS A");
        assertThat(sinFichas.timeProfile().unrecorded().goalsFor()).isEqualTo(80);
        assertThat(sinFichas.timeProfile().unrecorded().goalsAgainst()).isEqualTo(8);
        assertTotalsMatchRecord(sinFichas);
    }

    @Test
    void partidoSinFichaYFichaIncompletaVanASinMinuto() {
        CompetitionData full = data(779);
        Map<Integer, List<MatchEvent>> sheets = new HashMap<>();
        // 26390 (Vélez 2-4 Argentinos): sin ficha.
        // 26396 (Vélez 7-0 Comunicaciones): ficha a la que le falta un gol de Vélez.
        List<MatchEvent> incompleta = new java.util.ArrayList<>(full.sheets().get(26396));
        incompleta.remove(incompleta.stream().filter(e -> e.type() == Models.EventType.GOAL).findFirst().orElseThrow());
        sheets.put(26396, incompleta);
        CompetitionData partial = new CompetitionData(full.competition(), full.standings(), full.standingsDate(),
                full.matches(), full.players(), sheets);

        TeamReport velez = team(StatsEngine.build(VELEZ, "Primera Masculino", "Clausura", List.of(partial), HOY), "CAVS");
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
    void sinActasPublicadasNoHayPerfilPorMinuto() {
        TimeProfile tp = team(StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), HOY), "CAVS A")
                .timeProfile();

        assertThat(tp.hasData()).isFalse();
        assertThat(tp.matchesPlayed()).isEqualTo(9);
        assertThat(tp.periodMinutes()).isEqualTo(20);
        assertThat(tp.buckets()).hasSize(8);
    }

    @Test
    void enUnCruceAvsBElEventoSeAtribuyePorCodigo() {
        TeamRef velezA = new TeamRef(10, 74, "CAVS A", "VELEZ A");
        MatchEvent golDelB = new MatchEvent(1, 3, 0, Models.EventType.GOAL, 0, 74, "CAVS B", "");
        MatchEvent golDelA = new MatchEvent(1, 3, 0, Models.EventType.GOAL, 0, 74, "CAVS A", "");

        assertThat(StatsEngine.isOwn(golDelA, velezA, 11, 74)).isTrue();
        assertThat(StatsEngine.isOwn(golDelB, velezA, 11, 74)).isFalse();
        // Con team_id en el acta manda el id.
        assertThat(StatsEngine.isOwn(new MatchEvent(1, 3, 0, Models.EventType.BLUE_CARD, 11, 74, "", ""), velezA, 11, 74))
                .isFalse();
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
        // El código cambia entre torneos (Huracán: "HUR" en el Apertura 2026, "HURA" en el Clausura).
        assertThat(StatsEngine.teamKey(ref("HUR"), 1, "HURA")).isEqualTo("HURA");
        assertThat(StatsEngine.teamKey(ref("HURA"), 1, "HURA")).isEqualTo("HURA");
        assertThat(StatsEngine.teamKey(ref("HUR B"), 2, "HURA")).isEqualTo("HURA B");
    }

    @Test
    void cruceEntreAyBDelMismoClubCuentaParaAmbos() {
        ReportData report = StatsEngine.build(VELEZ, "Infantil Femenino", "Clausura", List.of(data(765)), HOY);

        assertThat(team(report, "CAVS A").rivals()).anySatisfy(r -> assertThat(r.rival()).isEqualTo("VELEZ B"));
        assertThat(team(report, "CAVS B").rivals()).anySatisfy(r -> assertThat(r.rival()).isEqualTo("VELEZ A"));
    }

    private static TeamRef ref(String code) {
        return new TeamRef(1, 1, code, code);
    }

    static TeamReport team(ReportData r, String code) {
        return r.teams().stream().filter(t -> t.code().equals(code)).findFirst().orElseThrow();
    }
}
