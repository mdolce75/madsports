package com.fppstats.stats;

import com.fppstats.stats.ReportData.ShortHandedMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Goles con uno menos: cuenta los goles que caen dentro de las sanciones (azul 2', roja 4'; la sanción se cumple
 * completa aunque haya gol, y una del final del 1T sigue en el 2T). Lo usan el informe de club (sanciones del equipo)
 * y el de jugador (sus propias sanciones). Tiempos en segundos desde el inicio del partido.
 */
public final class ShortHandedCalc {

    private final List<ShortHandedMatch> matches = new ArrayList<>();
    private int otherSeconds;
    private int otherGoalsAgainst;

    /**
     * Agrega un partido con acta.
     *
     * @param sanctions  intervalos [inicio, fin) de las sanciones (vacío si no hubo)
     * @param ownGoals   minuto (en segundos) de los goles propios
     * @param rivalGoals minuto (en segundos) de los goles del rival
     */
    public void add(java.time.LocalDate date, String rival, boolean home, List<int[]> sanctions, int blues, int reds,
                    List<Integer> ownGoals, List<Integer> rivalGoals, int matchSeconds, int finalFor, int finalAgainst) {
        List<int[]> union = union(sanctions, matchSeconds);
        int shortSeconds = length(union);
        int against = countIn(rivalGoals, union);
        otherSeconds += matchSeconds - shortSeconds;
        otherGoalsAgainst += rivalGoals.size() - against;
        if (shortSeconds > 0) {
            matches.add(new ShortHandedMatch(date, rival, home, describe(blues, reds),
                    StatsEngine.sanctionSeconds(sanctions, matchSeconds), against, countIn(ownGoals, union),
                    finalFor, finalAgainst));
        }
    }

    public ReportData.ShortHanded result() {
        List<ShortHandedMatch> byDate = matches.stream()
                .sorted(Comparator.comparing(ShortHandedMatch::date, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return new ReportData.ShortHanded(byDate, otherSeconds, otherGoalsAgainst);
    }

    /** Une los intervalos superpuestos y los corta al final del partido: el tiempo con al menos uno menos. */
    static List<int[]> union(List<int[]> intervals, int matchSeconds) {
        List<int[]> sorted = intervals.stream()
                .map(i -> new int[]{Math.max(0, i[0]), Math.min(i[1], matchSeconds)})
                .filter(i -> i[1] > i[0])
                .sorted(Comparator.comparingInt(i -> i[0]))
                .toList();
        List<int[]> merged = new ArrayList<>();
        for (int[] i : sorted) {
            if (!merged.isEmpty() && i[0] <= merged.getLast()[1]) {
                merged.getLast()[1] = Math.max(merged.getLast()[1], i[1]);
            } else {
                merged.add(new int[]{i[0], i[1]});
            }
        }
        return merged;
    }

    static int length(List<int[]> union) {
        return union.stream().mapToInt(i -> i[1] - i[0]).sum();
    }

    static int countIn(List<Integer> times, List<int[]> union) {
        return (int) times.stream().filter(t -> union.stream().anyMatch(i -> t >= i[0] && t < i[1])).count();
    }

    /** "1 azul", "2 azules", "1 azul y 1 roja". */
    static String describe(int blues, int reds) {
        List<String> parts = new ArrayList<>();
        if (blues > 0) {
            parts.add(blues + (blues == 1 ? " azul" : " azules"));
        }
        if (reds > 0) {
            parts.add(reds + (reds == 1 ? " roja" : " rojas"));
        }
        return String.join(" y ", parts);
    }
}
