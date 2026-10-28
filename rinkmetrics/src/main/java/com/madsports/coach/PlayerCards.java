package com.madsports.coach;

import com.madsports.coach.SquadService.SquadPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fichas de los jugadores de un informe (de un partido o de un período), con el mismo cálculo que la pantalla (player-cards.js): cada eje
 * va de 0 a 100 comparado con el resto del plantel (100 = el mejor del equipo en ese eje), los jugadores de campo entre
 * sí y los arqueros entre sí. El índice es el promedio de los cinco ejes.
 */
final class PlayerCards {

    /** Un eje de la ficha: su puntaje (0 a 100) y lo que se muestra ("1,5 goles por partido"). */
    public record Axis(String name, int score, String text) {
    }

    /** La ficha de un jugador. @param index el promedio de sus ejes; null si no tiene partidos en el período */
    public record Card(String key, String number, String name, boolean goalkeeper, int matches, Integer index,
                       List<Axis> axes) {
    }

    private static final Set<ActionType> FOR_US = EnumSet.of(ActionType.SHOT, ActionType.FREE_HIT_FOR,
            ActionType.PENALTY_FOR);
    private static final String[] FIELD = {"Ataque", "Precisión", "Desequilibrio", "Recuperación", "Disciplina"};
    private static final boolean[] FIELD_HIGH = {true, true, true, true, false};
    private static final String[] KEEPER = {"Atajadas", "Efectividad", "Pelota parada", "Valla", "Disciplina"};
    private static final boolean[] KEEPER_HIGH = {true, true, true, false, false};

    private PlayerCards() {
    }

    /** Lo de cada jugador: goles, tiros, recuperaciones, faltas, tarjetas, atajadas... */
    private static final class Tally {
        final Set<Long> matches = new HashSet<>();
        int goals, attempts, kicks, foulsRec, rec, turn, foulsCom, yellows, blues, reds, saves, received, spSaves, spTotal;

        static Tally of(List<MatchAction> list) {
            Tally c = new Tally();
            for (MatchAction a : list) {
                c.matches.add(a.getMatchId());
                ActionType k = a.getKind();
                Outcome o = a.getOutcome();
                if (FOR_US.contains(k)) {
                    c.attempts++;
                    if (o == Outcome.GOAL) {
                        c.goals++;
                    }
                    if (k != ActionType.SHOT) {
                        c.kicks++;
                    }
                }
                if (k == ActionType.FOUL) {
                    if (o == Outcome.RECEIVED) {
                        c.foulsRec++;
                    } else {
                        c.foulsCom++;
                    }
                }
                if (k == ActionType.RECOVERY) {
                    c.rec++;
                }
                if (k == ActionType.TURNOVER) {
                    c.turn++;
                }
                if (k == ActionType.CARD) {
                    if (o == Outcome.YELLOW) {
                        c.yellows++;
                    } else if (o == Outcome.BLUE) {
                        c.blues++;
                    } else {
                        c.reds++;
                    }
                }
                if (k == ActionType.SAVE) {
                    c.saves++;
                }
                if (k == ActionType.GOAL_CONCEDED) {
                    c.received++;
                }
                if (k == ActionType.FREE_HIT_AGAINST || k == ActionType.PENALTY_AGAINST) {
                    c.spTotal++;
                    if (o == Outcome.GOAL) {
                        c.received++;
                    } else {
                        c.spSaves++;
                    }
                }
            }
            return c;
        }

        int pj() {
            return matches.size();
        }

        double discipline() {
            return (foulsCom + yellows * 2 + blues * 3 + reds * 5) / (double) Math.max(1, pj());
        }
    }

    /** Un jugador con sus valores crudos (antes de compararlos con el plantel) y los textos de cada eje. */
    private record Raw(String key, String number, String name, boolean goalkeeper, int matches, double[] values,
                       String[] texts) {
    }

    static List<Card> compute(Collection<SquadPlayer> squad, List<MatchAction> actions) {
        Map<String, List<MatchAction>> byPlayer = new LinkedHashMap<>();
        for (MatchAction a : actions) {
            byPlayer.computeIfAbsent(CoachReport.playerKey(a), k -> new ArrayList<>()).add(a);
        }
        List<Raw> raws = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (SquadPlayer j : squad) {
            known.add("j" + j.id());
            raws.add(raw("j" + j.id(), j.number(), j.name(), j.goalkeeper(), byPlayer.getOrDefault("j" + j.id(), List.of())));
        }
        byPlayer.forEach((k, list) -> {
            if (known.contains(k)) {
                return;
            }
            long goalArea = list.stream().filter(a -> CoachReport.GOALKEEPER_ACTIONS.contains(a.getKind())).count();
            raws.add(raw(k, "", list.getFirst().getPlayerName(), goalArea * 2 > list.size(), list));
        });
        List<Card> cards = new ArrayList<>();
        for (boolean goalkeeper : new boolean[]{false, true}) {
            List<Raw> group = raws.stream().filter(r -> r.goalkeeper() == goalkeeper).toList();
            List<Raw> played = group.stream().filter(r -> r.matches() > 0).toList();
            String[] names = goalkeeper ? KEEPER : FIELD;
            boolean[] high = goalkeeper ? KEEPER_HIGH : FIELD_HIGH;
            for (Raw r : group) {
                List<Axis> axes = new ArrayList<>();
                for (int i = 0; i < names.length; i++) {
                    int axis = i;
                    double max = Math.max(0, played.stream().mapToDouble(p -> p.values()[axis]).max().orElse(0));
                    double min = played.stream().mapToDouble(p -> p.values()[axis]).min().orElse(0);
                    double v = r.values()[i];
                    int score = r.matches() == 0 ? 0 : high[i]
                            ? (max > 0 ? (int) Math.round(100 * v / max) : 0)
                            : (max > min ? (int) Math.round(100 * (max - v) / (max - min)) : 100);
                    axes.add(new Axis(names[i], score, r.texts()[i]));
                }
                Integer index = r.matches() == 0 ? null
                        : (int) Math.round(axes.stream().mapToInt(Axis::score).average().orElse(0));
                cards.add(new Card(r.key(), r.number(), r.name(), goalkeeper, r.matches(), index, axes));
            }
        }
        cards.sort(Comparator.comparingInt((Card c) -> c.index() == null ? -1 : c.index()).reversed()
                .thenComparingInt(c -> number(c.number())));
        return cards;
    }

    private static Raw raw(String key, String number, String name, boolean goalkeeper, List<MatchAction> list) {
        Tally c = Tally.of(list);
        double pj = Math.max(1, c.pj());
        if (!goalkeeper) {
            int shots = c.attempts;
            int balance = c.rec + c.turn;
            return new Raw(key, number, name, false, c.pj(), new double[]{
                    c.goals / pj, shots > 0 ? (double) c.goals / shots : 0, c.foulsRec / pj,
                    balance > 0 ? (double) c.rec / balance : 0, c.discipline()}, new String[]{
                    num(c.goals / pj) + (c.goals / pj == 1 ? " gol por partido" : " goles por partido"),
                    shots > 0 ? pct((double) c.goals / shots) + " convertidos" : "Sin tiros",
                    num(c.foulsRec / pj) + " por partido",
                    balance > 0 ? pct((double) c.rec / balance) + " de balance" : "Sin datos",
                    rating(c.discipline())});
        }
        int saved = c.saves + c.spSaves;
        return new Raw(key, number, name, true, c.pj(), new double[]{
                saved / pj, saved + c.received > 0 ? (double) saved / (saved + c.received) : 0,
                c.spTotal > 0 ? (double) c.spSaves / c.spTotal : 0, c.received / pj, c.discipline()}, new String[]{
                num(saved / pj) + " por partido",
                saved + c.received > 0 ? pct((double) saved / (saved + c.received)) + " atajados" : "Sin datos",
                c.spTotal > 0 ? pct((double) c.spSaves / c.spTotal) + " atajados" : "Sin libres ni penales",
                num(c.received / pj) + " por partido",
                rating(c.discipline())});
    }

    private static String rating(double v) {
        return v == 0 ? "Impecable" : v < 1 ? "Buena" : v < 2 ? "Regular" : "Para mejorar";
    }

    /** Con un decimal como mucho y coma decimal ("1,5", "2"). */
    private static String num(double v) {
        return new BigDecimal(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static int number(String n) {
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException e) {
            return 999;
        }
    }
}
