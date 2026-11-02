package com.madsports.compare;

import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.compare.Comparison.CardLine;
import com.madsports.compare.Comparison.HeadToHead;
import com.madsports.compare.Comparison.State;
import com.madsports.compare.ComparisonCalc.Input;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.stats.StatsEngine.CompetitionData;
import com.madsports.stats.StatsEngineTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El comparativo con datos reales de SIDGAD (Primera Masculino, Clausura 2026) y con partidos armados a mano. */
class ComparisonCalcTest {

    static final ClubView VELEZ = StatsEngineTest.VELEZ;
    static final ClubView COMU = new ClubView(69, "COMU", "COMUNICACIONES", List.of());
    static final ClubView AAAJ = new ClubView(94, "AAAJ", "ARGENTINOS JUNIORS", List.of());

    private static Comparison real(ClubView a, String keyA, ClubView b, String keyB) {
        List<CompetitionData> data = List.of(StatsEngineTest.data(779));
        return ComparisonCalc.build(Federation.FPP, 2026, "Primera Masculino", new Input(a, keyA, data, null),
                new Input(b, keyB, data, null), StatsEngineTest.TODAY, List.of());
    }

    @Test
    void withRealSidgadDataTheNumbersAreThoseOfEachTeamReport() {
        Comparison c = real(VELEZ, "CAVS", COMU, "COMU");

        assertThat(c.a().currentTournament()).isEqualTo("Clausura");
        assertThat(c.b().currentTournament()).isEqualTo("Clausura");
        // Un solo torneo en los datos: torneo actual y año son lo mismo.
        assertThat(c.a().current().report().total()).isEqualTo(c.a().season().report().total());
        assertThat(c.a().last5().total().played()).isLessThanOrEqualTo(Comparison.LAST_MATCHES);
        assertThat(c.notes()).isEmpty();

        // Vélez 7-0 Comunicaciones (acta 26396): los 7 goles con sus goleadores, todos de Vélez (local).
        HeadToHead played = c.headToHead().stream().filter(HeadToHead::played).findFirst().orElseThrow();
        assertThat(played.homeIsA()).isTrue();
        assertThat(played.goalsA()).isEqualTo(7);
        assertThat(played.goalsB()).isZero();
        assertThat(played.scorers()).allMatch(Comparison.Scorer::home);
        assertThat(played.scorers().stream().mapToInt(Comparison.Scorer::goals).sum()).isEqualTo(7);
        assertThat(c.summary()).anyMatch(s -> s.startsWith("Se enfrentaron una vez en el año: VELEZ ganó 1,")
                && s.endsWith("con 7 goles de VELEZ y 0 de COMUNICACIONES."));
    }

    @Test
    void theOtherWayRoundTheVisitorIsTeamA() {
        Comparison c = real(COMU, "COMU", VELEZ, "CAVS");

        HeadToHead played = c.headToHead().stream().filter(HeadToHead::played).findFirst().orElseThrow();
        assertThat(played.homeIsA()).isFalse();
        assertThat(played.goalsA()).isZero();
        assertThat(played.goalsB()).isEqualTo(7);
    }

    @Test
    void aTeamThatDoesNotPlayTheCategoryIsRejected() {
        ClubView nobody = new ClubView(9999, "XX", "NADIE", List.of());
        assertThatThrownBy(() -> real(VELEZ, "CAVS", nobody, "XX"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NADIE");
    }

    // --- Partidos armados: tarjetas con el marcador, cuerpo técnico, torneo actual y enfrentamientos pendientes.

    static final ClubView A = new ClubView(1, "AAA", "ALFA", List.of());
    static final ClubView B = new ClubView(2, "BBB", "BETA", List.of());
    static final ClubView C = new ClubView(3, "CCC", "GAMMA", List.of());

    private static Competition competition(int id, String tournament, int offset) {
        return Competition.of(id, "TORNEO " + tournament + " PRIMERA FEMENINO (FPP)", 3, 15, List.of(
                new TeamRef(offset + 1, 1, "AAA", "ALFA", "1.png"),
                new TeamRef(offset + 2, 2, "BBB", "BETA", "2.png"),
                new TeamRef(offset + 3, 3, "CCC", "GAMMA", "3.png")), 25);
    }

    private static Match match(int idp, int round, LocalDate date, int home, int away, Integer hg, Integer ag) {
        return new Match(idp, round, date, LocalTime.of(15, 30), "Cancha", home, (home % 10), name(home), away,
                (away % 10), name(away), hg, ag);
    }

    private static String name(int teamId) {
        return switch (teamId % 10) {
            case 1 -> "ALFA";
            case 2 -> "BETA";
            default -> "GAMMA";
        };
    }

    private static MatchEvent ev(int period, int minute, EventType type, int teamId, String player, int playerId) {
        return new MatchEvent(period, minute, 0, type, teamId, teamId % 10, name(teamId), player, playerId);
    }

    private static PlayerStats player(int id, int teamId, String name, int goals, int games) {
        return new PlayerStats(id, teamId, name(teamId), teamId % 10, name, goals, games, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Apertura (10x) y Clausura (20x); el último partido de los dos es del Clausura y queda un Alfa-Beta pendiente. */
    private static List<CompetitionData> season() {
        Competition apertura = competition(500, "APERTURA", 100);
        Competition clausura = competition(600, "CLAUSURA", 200);
        Map<Integer, List<MatchEvent>> sheetsA = new HashMap<>();
        // Alfa 3-1 Beta: Beta empata 1-1, Alfa se pone 2-1 y Beta ve una azul perdiendo; amarilla del DT de Alfa.
        sheetsA.put(1, List.of(
                ev(1, 3, EventType.GOAL, 101, "Ana Alfa", 11),
                ev(1, 9, EventType.GOAL, 102, "Bea Beta", 21),
                ev(1, 12, EventType.YELLOW_CARD, 101, "Ana Alfa", 11),
                ev(2, 1, EventType.GOAL, 101, "Ana Alfa", 11),
                ev(2, 4, EventType.BLUE_CARD, 102, "Bea Beta", 21),
                ev(2, 20, EventType.GOAL, 101, "Clara Alfa", 12),
                ev(2, 22, EventType.YELLOW_CARD, 101, "Pedro Técnico", 0)));
        CompetitionData ap = new CompetitionData(apertura, List.of(), null, List.of(
                match(1, 1, LocalDate.of(2026, 4, 4), 101, 102, 3, 1),
                match(2, 2, LocalDate.of(2026, 4, 11), 103, 101, 0, 2),
                match(3, 3, LocalDate.of(2026, 4, 18), 102, 103, 2, 2)),
                List.of(player(11, 101, "Ana Alfa", 2, 2), player(12, 101, "Clara Alfa", 1, 2),
                        player(21, 102, "Bea Beta", 1, 2)), sheetsA, Set.of());
        Map<Integer, List<MatchEvent>> sheetsC = new HashMap<>();
        sheetsC.put(4, List.of(ev(1, 2, EventType.GOAL, 202, "Bea Beta", 21),
                ev(1, 5, EventType.GOAL, 202, "Bea Beta", 21),
                ev(1, 6, EventType.RED_CARD, 201, "Clara Alfa", 12),
                ev(2, 10, EventType.GOAL, 201, "Ana Alfa", 11),
                ev(2, 15, EventType.GOAL, 201, "Ana Alfa", 11)));
        CompetitionData cl = new CompetitionData(clausura, List.of(), null, List.of(
                match(4, 1, LocalDate.of(2026, 8, 8), 202, 201, 2, 2),
                match(5, 2, LocalDate.of(2026, 8, 15), 201, 203, 4, 0),
                match(6, 3, LocalDate.of(2026, 8, 22), 203, 202, 1, 0),
                match(7, 4, LocalDate.of(2026, 11, 20), 201, 202, null, null)),
                List.of(player(11, 201, "Ana Alfa", 3, 2), player(12, 201, "Clara Alfa", 1, 2),
                        player(21, 202, "Bea Beta", 2, 2)), sheetsC, Set.of());
        return new ArrayList<>(List.of(ap, cl));
    }

    private static Comparison built() {
        List<CompetitionData> data = season();
        return ComparisonCalc.build(Federation.FPP, 2026, "Primera Femenino", new Input(A, "AAA", data, null),
                new Input(B, "BBB", data, null), LocalDate.of(2026, 11, 2), List.of());
    }

    @Test
    void currentTournamentSeasonAndLastFive() {
        Comparison c = built();

        assertThat(c.a().currentTournament()).isEqualTo("Clausura");
        assertThat(c.a().current().report().total().played()).isEqualTo(2);
        assertThat(c.a().current().report().total().won()).isEqualTo(1);
        assertThat(c.a().current().report().total().drawn()).isEqualTo(1);
        assertThat(c.a().season().report().total().played()).isEqualTo(4);
        assertThat(c.a().season().report().total().goalsFor()).isEqualTo(11);
        assertThat(c.b().season().report().total().played()).isEqualTo(4);
        assertThat(c.a().last5().total().played()).isEqualTo(4);
    }

    @Test
    void eachCardWithTheScoreAndTheStaffApart() {
        Comparison c = built();

        List<CardLine> alfa = c.a().season().cards();
        assertThat(alfa).extracting(CardLine::player, CardLine::card, CardLine::score, CardLine::state, CardLine::staff)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Ana Alfa", "Amarilla", "1-1", State.LEVEL, false),
                        org.assertj.core.groups.Tuple.tuple("Pedro Técnico", "Amarilla", "3-1", State.AHEAD, true),
                        org.assertj.core.groups.Tuple.tuple("Clara Alfa", "Roja", "0-2", State.BEHIND, false));
        assertThat(alfa.get(2).minuteLabel()).isEqualTo("1T 06:00");
        assertThat(alfa.get(2).opponent()).isEqualTo("BETA");
        // El técnico va en la lista pero no en los conteos de jugadores.
        assertThat(c.a().season().cardStates()).containsExactly(0, 1, 1);
        // En el torneo actual, solo la roja del Clausura.
        assertThat(c.a().current().cards()).extracting(CardLine::card).containsExactly("Roja");

        assertThat(c.b().season().cards()).singleElement()
                .satisfies(card -> assertThat(card.state()).isEqualTo(State.BEHIND));
    }

    @Test
    void headToHeadAndSummary() {
        Comparison c = built();

        assertThat(c.headToHead()).extracting(HeadToHead::tournament, HeadToHead::goalsA, HeadToHead::goalsB)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Apertura", 3, 1),
                        org.assertj.core.groups.Tuple.tuple("Clausura", 2, 2),
                        org.assertj.core.groups.Tuple.tuple("Clausura", null, null));
        assertThat(c.headToHead().getFirst().scorers()).extracting(Comparison.Scorer::player, Comparison.Scorer::goals)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Ana Alfa", 2),
                        org.assertj.core.groups.Tuple.tuple("Clara Alfa", 1),
                        org.assertj.core.groups.Tuple.tuple("Bea Beta", 1));
        assertThat(c.summary()).containsExactly(
                "ALFA llega mejor: en sus últimos 5 partidos ALFA ganó 3, empató 1 y perdió 0 (11 goles a favor, 3 en"
                        + " contra) y BETA ganó 0, empató 2 y perdió 2 (5 goles a favor, 8 en contra).",
                "Se enfrentaron 2 veces en el año: ALFA ganó 1 y empataron 1, con 5 goles de ALFA y 3 de BETA.",
                "El próximo enfrentamiento es por la fecha 4 del Clausura, el 20/11, en cancha de ALFA.");
    }

    @Test
    void teamsInDifferentCompetitionsGetANote() {
        List<CompetitionData> data = season();
        // Beta juega el Clausura en otra competición (otra zona): solo comparten el Apertura.
        CompetitionData cl = data.get(1);
        Competition other = competition(700, "CLAUSURA", 300);
        CompetitionData betaZone = new CompetitionData(other, List.of(), null,
                List.of(match(8, 1, LocalDate.of(2026, 9, 1), 302, 303, 1, 0)), List.of(), new HashMap<>(), Set.of());
        Comparison c = ComparisonCalc.build(Federation.FPP, 2026, "Primera Femenino",
                new Input(A, "AAA", List.of(data.get(0), cl), null),
                new Input(B, "BBB", List.of(data.get(0), betaZone), null), LocalDate.of(2026, 11, 2), List.of());

        assertThat(c.notes()).singleElement().asString().contains("no son del mismo torneo");
    }
}
