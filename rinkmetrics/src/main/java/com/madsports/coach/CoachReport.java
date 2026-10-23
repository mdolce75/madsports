package com.madsports.coach;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.madsports.coach.SquadService.SquadPlayer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Informe del DT: de un partido o de varios (un mes, un bimestre, un semestre o un campeonato). Lleva las métricas del
 * equipo, las de cada jugador y, en el de un partido, la lista de eventos. Con un jugador elegido, todo es de ese
 * jugador. La pantalla arma lo mismo en {@code js/coach/report.js} (para mostrarlo en vivo y sin señal); este es el del
 * PDF.
 *
 * @param titulo    "VELEZ 3 - 2 HURACAN" o "Octubre 2026"
 * @param detalle   equipo, condición, campeonato y fecha; o equipo y cantidad de partidos
 * @param jugador   el jugador elegido (null: todo el equipo)
 * @param eventos   solo en el informe de un partido
 */
public record CoachReport(String title, String detail, String club, String team, LocalDate generatedAt,
                        boolean singleMatch, List<ReportMatchRow> matches, Balance balance, Metrics totals,
                        List<PlayerRow> players, PlayerRow player, List<ReportEvent> events,
                        Metrics firstHalf, Metrics secondHalf, List<PlayerCards.Card> playerCards) {

    /** Un partido del informe. @param resultado G, E o P */
    public record ReportMatchRow(Long id, LocalDate date, String opponent, boolean home, String tournament, int goalsFor,
                              int goalsAgainst, String outcome) {

        static ReportMatchRow of(LiveMatch p) {
            int gf = p.getGoalsFor(), gc = p.getGoalsAgainst();
            return new ReportMatchRow(p.getId(), p.getDate(), p.getOpponent(), p.isHome(), p.getTournament(), gf, gc,
                    gf > gc ? "G" : gf == gc ? "E" : "P");
        }
    }

    /** Partidos jugados, ganados, empatados y perdidos, y los goles. */
    public record Balance(int played, int won, int drawn, int lost, int goalsFor, int goalsAgainst) {

        static Balance de(List<ReportMatchRow> ps) {
            return new Balance(ps.size(), (int) ps.stream().filter(p -> p.outcome().equals("G")).count(),
                    (int) ps.stream().filter(p -> p.outcome().equals("E")).count(),
                    (int) ps.stream().filter(p -> p.outcome().equals("P")).count(),
                    ps.stream().mapToInt(ReportMatchRow::goalsFor).sum(),
                    ps.stream().mapToInt(ReportMatchRow::goalsAgainst).sum());
        }
    }

    /**
     * Las métricas del informe. Goles: tiros, libres y penales convertidos. Goles recibidos y atajadas (con libres y
     * penales atajados): del arquero. Pérdidas y recuperaciones: en defensa (campo propio) y en ataque (campo rival).
     */
    public record Metrics(int goals, int goalsConceded, int shots, int shotGoals, int saves, int turnoversDefense,
                           int turnoversAttack, int recoveriesDefense, int recoveriesAttack,
                           int foulsReceived, int foulsCommitted, int blues, int yellows, int reds,
                           int penalties, int penaltyGoals, int freeHits, int freeHitGoals) {

        static Metrics de(Collection<MatchAction> acc) {
            return new Metrics(
                    (int) acc.stream().filter(a -> a.getKind().isGoalFor(a.getOutcome())).count(),
                    (int) acc.stream().filter(a -> a.getKind().isGoalAgainst(a.getOutcome())).count(),
                    n(acc, ActionType.SHOT, null), n(acc, ActionType.SHOT, Outcome.GOAL),
                    n(acc, ActionType.SAVE, null) + n(acc, ActionType.FREE_HIT_AGAINST, Outcome.SAVED)
                            + n(acc, ActionType.PENALTY_AGAINST, Outcome.SAVED),
                    n(acc, ActionType.TURNOVER, Outcome.OWN_HALF), n(acc, ActionType.TURNOVER, Outcome.RIVAL_HALF),
                    n(acc, ActionType.RECOVERY, Outcome.OWN_HALF), n(acc, ActionType.RECOVERY, Outcome.RIVAL_HALF),
                    n(acc, ActionType.FOUL, Outcome.RECEIVED), n(acc, ActionType.FOUL, Outcome.COMMITTED),
                    n(acc, ActionType.CARD, Outcome.BLUE), n(acc, ActionType.CARD, Outcome.YELLOW),
                    n(acc, ActionType.CARD, Outcome.RED),
                    n(acc, ActionType.PENALTY_FOR, null), n(acc, ActionType.PENALTY_FOR, Outcome.GOAL),
                    n(acc, ActionType.FREE_HIT_FOR, null), n(acc, ActionType.FREE_HIT_FOR, Outcome.GOAL));
        }

        private static int n(Collection<MatchAction> acc, ActionType kind, Outcome res) {
            return (int) acc.stream().filter(a -> a.getKind() == kind && (res == null || a.getOutcome() == res))
                    .count();
        }

        @JsonProperty
        public int turnovers() {
            return turnoversDefense + turnoversAttack;
        }

        @JsonProperty
        public int recoveries() {
            return recoveriesDefense + recoveriesAttack;
        }

        /** Porcentaje de tiros convertidos (null sin tiros). */
        @JsonProperty
        public Integer shotEffectiveness() {
            return pct(shotGoals, shots);
        }

        @JsonProperty
        public Integer penaltyEffectiveness() {
            return pct(penaltyGoals, penalties);
        }

        @JsonProperty
        public Integer freeHitEffectiveness() {
            return pct(freeHitGoals, freeHits);
        }

        private static Integer pct(int a, int b) {
            return b == 0 ? null : (int) Math.round(100.0 * a / b);
        }

        /** Qué parte de las pérdidas del equipo (team) son de estas (0 a 100; null si el equipo no tuvo). */
        public Integer shareOfTurnovers(Metrics team) {
            return pct(turnovers(), team.turnovers());
        }

        /** Qué parte de las recuperaciones del equipo (team) son de estas (0 a 100; null si el equipo no tuvo). */
        public Integer shareOfRecoveries(Metrics team) {
            return pct(recoveries(), team.recoveries());
        }
    }

    /**
     * Un jugador del informe. @param clave "j" + id de SIDGAD, o "m" + nombre para un jugador agregado a mano
     *
     * @param partidos partidos en los que tiene acciones
     * @param firstHalf sus métricas en el primer tiempo (secondHalf: en el segundo; metrics: en todo el partido)
     */
    public record PlayerRow(String key, String number, String name, boolean goalkeeper, int matches,
                              Metrics metrics, Metrics firstHalf, Metrics secondHalf) {
    }

    /**
     * Un puesto de un ranking del informe de un período.
     *
     * @param total   lo que suma el jugador en el período (tiros, pérdidas o recuperaciones)
     * @param perGame promedio por partido con acciones
     * @param share   qué parte del total del equipo es suya (0 a 100)
     */
    public record RankingRow(int position, String number, String name, int matches, int total, double perGame,
                             Integer share) {
    }

    /** Ranking de situaciones de gol (tiros al arco). */
    public List<RankingRow> rankingShots() {
        return ranking(Metrics::shots, totals.shots());
    }

    /** Top 5 de goleadores. */
    public List<RankingRow> topGoals() {
        return ranking(Metrics::goals, totals.goals()).stream().limit(5).toList();
    }

    /** Top 5 de faltas cometidas. */
    public List<RankingRow> topFouls() {
        return ranking(Metrics::foulsCommitted, totals.foulsCommitted()).stream().limit(5).toList();
    }

    /** Un puesto del top de tarjetas: por color, el total y el promedio por partido. */
    public record CardRow(int position, String number, String name, int matches, int blues, int yellows, int reds,
                          int total, double perGame) {
    }

    /** Top 5 de tarjetas (todas los colores juntos; a igual total, el de más por partido primero). */
    public List<CardRow> topCards() {
        List<PlayerRow> ordered = players.stream().filter(j -> cards(j.metrics()) > 0)
                .sorted(Comparator.comparingInt((PlayerRow j) -> cards(j.metrics())).reversed()
                        .thenComparing(Comparator.comparingDouble((PlayerRow j) ->
                                (double) cards(j.metrics()) / Math.max(1, j.matches())).reversed()))
                .limit(5).toList();
        List<CardRow> out = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            PlayerRow j = ordered.get(i);
            Metrics m = j.metrics();
            out.add(new CardRow(i + 1, j.number(), j.name(), j.matches(), m.blues(), m.yellows(), m.reds(), cards(m),
                    j.matches() == 0 ? 0 : (double) cards(m) / j.matches()));
        }
        return out;
    }

    private static int cards(Metrics m) {
        return m.blues() + m.yellows() + m.reds();
    }

    /** Un puesto del top de pelota parada: convertidos, pateados y efectividad. */
    public record SetPieceRow(int position, String number, String name, int scored, int taken, int effectiveness) {
    }

    /** Top 3 en penales a favor, por efectividad (a igual efectividad, el que más pateó). */
    public List<SetPieceRow> topPenalties() {
        return setPieces(Metrics::penaltyGoals, Metrics::penalties);
    }

    /** Top 3 en libres a favor, por efectividad (a igual efectividad, el que más pateó). */
    public List<SetPieceRow> topFreeHits() {
        return setPieces(Metrics::freeHitGoals, Metrics::freeHits);
    }

    private List<SetPieceRow> setPieces(java.util.function.ToIntFunction<Metrics> scored,
                                        java.util.function.ToIntFunction<Metrics> taken) {
        List<PlayerRow> ordered = players.stream().filter(j -> taken.applyAsInt(j.metrics()) > 0)
                .sorted(Comparator.comparingInt((PlayerRow j) -> effectiveness(scored, taken, j)).reversed()
                        .thenComparing(Comparator.comparingInt((PlayerRow j) -> taken.applyAsInt(j.metrics())).reversed())
                        .thenComparing(PlayerRow::name, SquadService.ALPHABETICAL))
                .limit(3).toList();
        List<SetPieceRow> out = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            PlayerRow j = ordered.get(i);
            out.add(new SetPieceRow(i + 1, j.number(), j.name(), scored.applyAsInt(j.metrics()),
                    taken.applyAsInt(j.metrics()), effectiveness(scored, taken, j)));
        }
        return out;
    }

    private static int effectiveness(java.util.function.ToIntFunction<Metrics> scored,
                                     java.util.function.ToIntFunction<Metrics> taken, PlayerRow j) {
        return (int) Math.round(100.0 * scored.applyAsInt(j.metrics()) / taken.applyAsInt(j.metrics()));
    }

    /** Ranking de pérdidas. */
    public List<RankingRow> rankingTurnovers() {
        return ranking(Metrics::turnovers, totals.turnovers());
    }

    /** Ranking de recuperaciones. */
    public List<RankingRow> rankingRecoveries() {
        return ranking(Metrics::recoveries, totals.recoveries());
    }

    /** Los jugadores con algo de esa métrica, de más a menos (a igual total, el de mejor promedio primero). */
    private List<RankingRow> ranking(java.util.function.ToIntFunction<Metrics> metric, int teamTotal) {
        List<PlayerRow> ordered = players.stream().filter(j -> metric.applyAsInt(j.metrics()) > 0)
                .sorted(Comparator.comparingInt((PlayerRow j) -> metric.applyAsInt(j.metrics())).reversed()
                        .thenComparing(Comparator.comparingDouble((PlayerRow j) ->
                                (double) metric.applyAsInt(j.metrics()) / Math.max(1, j.matches())).reversed()))
                .toList();
        List<RankingRow> out = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            PlayerRow j = ordered.get(i);
            int total = metric.applyAsInt(j.metrics());
            out.add(new RankingRow(i + 1, j.number(), j.name(), j.matches(), total,
                    j.matches() == 0 ? 0 : (double) total / j.matches(),
                    teamTotal == 0 ? null : (int) Math.round(100.0 * total / teamTotal)));
        }
        return out;
    }

    /** Un evento del partido. @param minuto tiempo jugado del período ("1T 12:18") */
    public record ReportEvent(int period, int atSecond, String minute, String key, String number, String player,
                         String action, String color) {
    }

    static final Set<ActionType> GOALKEEPER_ACTIONS = Set.of(ActionType.SAVE, ActionType.GOAL_CONCEDED,
            ActionType.FREE_HIT_AGAINST, ActionType.PENALTY_AGAINST);

    static String playerKey(MatchAction a) {
        return a.getPlayerId() > 0 ? "j" + a.getPlayerId() : "m" + a.getPlayerName();
    }

    /**
     * Arma el informe.
     *
     * @param ps       los partidos (uno solo en el informe de un partido)
     * @param acciones las activas de esos partidos, en orden de juego
     * @param plantel  para el nombre actual, el número y si es arquero (los dados de baja no están)
     * @param clave    el jugador elegido, o null
     */
    static CoachReport build(String title, String detail, String club, String team, LocalDate generatedAt,
                           boolean singleMatch, List<LiveMatch> ps, List<MatchAction> actions,
                           Map<Integer, SquadPlayer> squad, String key) {
        Map<String, List<MatchAction>> byPlayer = new LinkedHashMap<>();
        for (MatchAction a : actions) {
            byPlayer.computeIfAbsent(playerKey(a), k -> new ArrayList<>()).add(a);
        }
        List<PlayerRow> players = new ArrayList<>();
        for (var e : byPlayer.entrySet()) {
            MatchAction one = e.getValue().getFirst();
            SquadPlayer pj = one.getPlayerId() > 0 ? squad.get(one.getPlayerId()) : null;
            boolean goalkeeper = pj != null ? pj.goalkeeper() : e.getValue().stream().anyMatch(a -> GOALKEEPER_ACTIONS.contains(a.getKind()));
            players.add(new PlayerRow(e.getKey(), pj == null ? "" : pj.number(),
                    pj == null ? one.getPlayerName() : pj.name(), goalkeeper,
                    (int) e.getValue().stream().map(MatchAction::getMatchId).distinct().count(),
                    Metrics.de(e.getValue()), Metrics.de(inPeriod(e.getValue(), 1)), Metrics.de(inPeriod(e.getValue(), 2))));
        }
        players.sort(Comparator.comparingInt((PlayerRow j) -> number(j.number()))
                .thenComparing(PlayerRow::name, SquadService.ALPHABETICAL));

        PlayerRow chosen = null;
        List<MatchAction> thoseOf = actions;
        if (key != null && !key.isBlank()) {
            chosen = players.stream().filter(j -> j.key().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Ese jugador no tiene acciones en el informe."));
            thoseOf = byPlayer.get(key);
            players = List.of(chosen);
        }
        Map<String, PlayerRow> byKey = players.stream().collect(Collectors.toMap(PlayerRow::key,
                j -> j, (a, b) -> a));
        int duration = ps.isEmpty() ? CoachService.DEFAULT_MINUTES * 60 : ps.getFirst().duration();
        List<ReportEvent> events = !singleMatch ? List.of() : thoseOf.stream().map(a -> {
            var n = name(a.getKind(), a.getOutcome());
            return new ReportEvent(a.getPeriod(), a.getAtSecond(), a.getPeriod() + "T " + mmss(Math.min(a.getAtSecond(), duration)),
                    playerKey(a), byKey.containsKey(playerKey(a)) ? byKey.get(playerKey(a)).number() : "",
                    byKey.containsKey(playerKey(a)) ? byKey.get(playerKey(a)).name() : a.getPlayerName(), n[0], n[1]);
        }).toList();
        List<ReportMatchRow> rows = ps.stream().map(ReportMatchRow::of).toList();
        return new CoachReport(title, detail, club, team, generatedAt, singleMatch, rows, Balance.de(rows),
                Metrics.de(thoseOf), players, chosen, events, Metrics.de(inPeriod(thoseOf, 1)),
                Metrics.de(inPeriod(thoseOf, 2)),
                cards(squad, actions, singleMatch, chosen));
    }

    /**
     * Fichas del informe: las de todo el plantel en el de un período; con un jugador elegido, solo la suya (comparado
     * con el plantel en los mismos partidos). El informe de un partido del equipo no las lleva.
     */
    private static List<PlayerCards.Card> cards(Map<Integer, SquadPlayer> squad, List<MatchAction> actions,
                                                boolean singleMatch, PlayerRow chosen) {
        if (chosen == null && singleMatch) {
            return List.of();
        }
        List<PlayerCards.Card> all = PlayerCards.compute(squad.values(), actions);
        return chosen == null ? all : all.stream().filter(c -> c.key().equals(chosen.key())).toList();
    }

    /** Las acciones de un tiempo (1 o 2). */
    private static List<MatchAction> inPeriod(List<MatchAction> actions, int period) {
        return actions.stream().filter(a -> a.getPeriod() == period).toList();
    }

    private static int number(String n) {
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException e) {
            return 999;
        }
    }

    static String mmss(int s) {
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    /** Nombre completo de la acción y su color, como en los botones de la pantalla de carga. */
    static String[] name(ActionType kind, Outcome res) {
        return switch (kind) {
            case TURNOVER -> res == Outcome.OWN_HALF ? a("Pérdida en defensa", "#b91c1c") : a("Pérdida en ataque", "#c2410c");
            case RECOVERY -> res == Outcome.OWN_HALF ? a("Recuperación en defensa", "#15803d")
                    : a("Recuperación en ataque", "#4d7c0f");
            case SHOT -> res == Outcome.GOAL ? a("Tiro al arco: Gol", "#0f766e") : a("Tiro al arco: No gol", "#52525b");
            case SAVE -> a("Atajada", "#1d4ed8");
            case GOAL_CONCEDED -> a("Gol recibido", "#9f1239");
            case FREE_HIT_FOR -> res == Outcome.GOAL ? a("Libre a favor: Gol", "#0f766e") : a("Libre a favor: No gol", "#52525b");
            case FREE_HIT_AGAINST -> res == Outcome.GOAL ? a("Libre en contra: Gol", "#9f1239")
                    : a("Libre en contra: Atajado", "#1d4ed8");
            case PENALTY_FOR -> res == Outcome.GOAL ? a("Penal a favor: Gol", "#0f766e") : a("Penal a favor: No gol", "#52525b");
            case PENALTY_AGAINST -> res == Outcome.GOAL ? a("Penal en contra: Gol", "#9f1239")
                    : a("Penal en contra: Atajado", "#1d4ed8");
            case FOUL -> res == Outcome.COMMITTED ? a("Falta cometida", "#6d28d9") : a("Falta recibida", "#0369a1");
            case CARD -> switch (res) {
                case BLUE -> a("Tarjeta azul", "#1d4ed8");
                case RED -> a("Tarjeta roja", "#dc2626");
                default -> a("Tarjeta amarilla", "#facc15");
            };
        };
    }

    private static String[] a(String name, String color) {
        return new String[]{name, color};
    }
}
