package com.madsports.stats;

import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.stats.ReportData.FoulSituation;
import com.madsports.stats.ReportData.ScoreFlow;
import com.madsports.stats.ReportData.TimeBucket;
import com.madsports.stats.ReportData.TimeProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * El perfil por minuto de un equipo con las actas de sus partidos: goles, tarjetas y faltas por tramo de 5', cómo iba
 * el marcador, el tiempo con uno menos y con uno más, y el marcador partido a partido. Sin I/O.
 */
final class TimeProfileCalc {

    static final int BUCKET_MINUTES = 5;

    /** Tiempo que el equipo juega con uno menos por cada sanción (reglamento de hockey sobre patines). */
    static final int BLUE_CARD_SECONDS = 2 * 60;
    static final int RED_CARD_SECONDS = 4 * 60;

    /** Período que SIDGAD usa para el descanso (config_params: all_periods:1,10,2). */
    static final int HALFTIME_PERIOD = 10;

    /** Columnas por tramo: goles, tarjetas (amarilla, azul, roja) y faltas; propias y de los rivales. */
    private static final int COLS = 10;

    /** Suma, por tramo de 5', goles y tarjetas propias y de los rivales en todos los partidos con acta. */
    static TimeProfile timeProfile(List<TeamEntry> entries, Set<Match> included) {
        int periodMinutes = entries.stream().mapToInt(e -> e.data().competition().periodMinutes()).max()
                .orElse(Competition.DEFAULT_PERIOD_MINUTES);
        int perPeriod = (periodMinutes + BUCKET_MINUTES - 1) / BUCKET_MINUTES;
        int[][] counts = new int[2 * perPeriod][COLS];
        // Marcador al empezar cada tramo: suma de diferencias y partidos ganando / empatando / perdiendo.
        double[] diffSum = new double[2 * perPeriod];
        int[][] situation = new int[2 * perPeriod][3];
        int played = 0;
        int withSheet = 0;
        int mismatches = 0;
        int unrecordedFor = 0;
        int unrecordedAgainst = 0;
        int shortHanded = 0;
        int powerPlay = 0;
        int[] unrecordedCards = new int[COLS];
        // Faltas propias según el marcador en el minuto de la falta, y segundos en cada situación.
        int[] foulsBySituation = new int[3];
        int[] secondsBySituation = new int[3];
        ShortHandedCalc shortHandedGoals = new ShortHandedCalc();
        // El marcador partido a partido: [0] partidos con acta completa; [1..3] estuvo arriba y ganó / empató / perdió;
        // [4..6] estuvo abajo; [7..9] convirtió primero; [10..12] le convirtieron primero; [13] y [14] puntos ganados y
        // perdidos en los últimos 5 minutos.
        int[] flow = new int[15];

        for (TeamEntry e : entries) {
            TeamRef own = e.team();
            for (Match m : e.data().matches()) {
                if (!m.involves(own.teamId()) || !included.contains(m)) {
                    continue;
                }
                played++;
                boolean home = m.homeTeamId() == own.teamId();
                int resultFor = home ? m.homeGoals() : m.awayGoals();
                int resultAgainst = home ? m.awayGoals() : m.homeGoals();
                List<MatchEvent> events = e.data().sheets().getOrDefault(m.idp(), List.of());
                if (events.isEmpty()) {
                    // Sin ficha: se conoce el resultado pero no el minuto de los goles ni las tarjetas.
                    unrecordedFor += resultFor;
                    unrecordedAgainst += resultAgainst;
                    continue;
                }
                withSheet++;
                int rivalTeamId = home ? m.awayTeamId() : m.homeTeamId();
                int rivalClubId = home ? m.awayClubId() : m.homeClubId();
                int goalsInSheet = 0;
                int ownGoalsInSheet = 0;
                int rivalGoalsInSheet = 0;
                int matchPeriod = e.data().competition().periodMinutes();
                List<int[]> ownSanctions = new ArrayList<>();
                List<int[]> rivalSanctions = new ArrayList<>();
                List<Integer> ownGoalTimes = new ArrayList<>();
                List<Integer> rivalGoalTimes = new ArrayList<>();
                List<Integer> ownFoulTimes = new ArrayList<>();
                int ownBlues = 0;
                int ownReds = 0;
                for (MatchEvent ev : events) {
                    boolean ours = isOwn(ev, own, rivalTeamId, rivalClubId);
                    int column = switch (ev.type()) {
                        case GOAL -> ours ? 0 : 1;
                        case YELLOW_CARD -> ours ? 2 : 5;
                        case BLUE_CARD -> ours ? 3 : 6;
                        case RED_CARD -> ours ? 4 : 7;
                        case FOUL -> ours ? 8 : 9;
                    };
                    // SIDGAD: período 10 = descanso (la sanción se cumple desde el inicio del 2T); 11 y otros = fuera
                    // del juego (p. ej. después del partido): la tarjeta cuenta, pero sin minuto ni inferioridad.
                    int period = ev.period() == HALFTIME_PERIOD ? 2 : ev.period();
                    int minute = ev.period() == HALFTIME_PERIOD ? 0 : ev.minute();
                    int second = ev.period() == HALFTIME_PERIOD ? 0 : ev.second();
                    if (period < 1 || period > 2) {
                        if (ev.type() != EventType.GOAL) {
                            unrecordedCards[column]++;
                        }
                        continue;
                    }
                    int bucket = (period - 1) * perPeriod + Math.min(minute / BUCKET_MINUTES, perPeriod - 1);
                    counts[bucket][column]++;
                    int start = (period - 1) * matchPeriod * 60 + minute * 60 + second;
                    int seconds = ev.type() == EventType.BLUE_CARD ? BLUE_CARD_SECONDS
                            : ev.type() == EventType.RED_CARD ? RED_CARD_SECONDS : 0;
                    if (seconds > 0) {
                        (ours ? ownSanctions : rivalSanctions).add(new int[]{start, start + seconds});
                        if (ours) {
                            ownBlues += ev.type() == EventType.BLUE_CARD ? 1 : 0;
                            ownReds += ev.type() == EventType.RED_CARD ? 1 : 0;
                        }
                    }
                    if (ev.type() == EventType.FOUL && ours) {
                        ownFoulTimes.add(start);
                    }
                    if (ev.type() == EventType.GOAL) {
                        goalsInSheet++;
                        if (ours) {
                            ownGoalsInSheet++;
                            ownGoalTimes.add(start);
                        } else {
                            rivalGoalsInSheet++;
                            rivalGoalTimes.add(start);
                        }
                    }
                }
                if (goalsInSheet != m.homeGoals() + m.awayGoals()) {
                    mismatches++;
                } else {
                    scoreFlow(ownGoalTimes, rivalGoalTimes, resultFor, resultAgainst, 2 * matchPeriod * 60, flow);
                }
                // Cómo iba el partido al empezar cada tramo, según el minuto de los goles de la ficha.
                for (int i = 0; i < 2 * perPeriod; i++) {
                    int startOfBucket = (i / perPeriod) * matchPeriod * 60 + (i % perPeriod) * BUCKET_MINUTES * 60;
                    int diff = (int) ownGoalTimes.stream().filter(t -> t < startOfBucket).count()
                            - (int) rivalGoalTimes.stream().filter(t -> t < startOfBucket).count();
                    diffSum[i] += diff;
                    situation[i][diff > 0 ? 0 : diff == 0 ? 1 : 2]++;
                }
                // Faltas según el marcador y tiempo ganando / empatando / perdiendo.
                for (int t : ownFoulTimes) {
                    int diff = (int) ownGoalTimes.stream().filter(g -> g < t).count()
                            - (int) rivalGoalTimes.stream().filter(g -> g < t).count();
                    foulsBySituation[situationIndex(diff)]++;
                }
                accumulateSituationSeconds(ownGoalTimes, rivalGoalTimes, 2 * matchPeriod * 60, secondsBySituation);
                // Ficha incompleta: los goles del resultado que no figuran en ella quedan sin minuto.
                unrecordedFor += Math.max(0, resultFor - ownGoalsInSheet);
                unrecordedAgainst += Math.max(0, resultAgainst - rivalGoalsInSheet);
                shortHanded += sanctionSeconds(ownSanctions, 2 * matchPeriod * 60);
                shortHandedGoals.add(m.date(), home ? m.awayName() : m.homeName(), home, ownSanctions, ownBlues, ownReds,
                        ownGoalTimes, rivalGoalTimes, 2 * matchPeriod * 60, resultFor, resultAgainst);
                powerPlay += sanctionSeconds(rivalSanctions, 2 * matchPeriod * 60);
            }
        }

        double sheets = Math.max(1, withSheet);
        List<TimeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            int period = i / perPeriod + 1;
            int from = (i % perPeriod) * BUCKET_MINUTES;
            int to = Math.min(from + BUCKET_MINUTES, periodMinutes);
            buckets.add(bucket(period + "T " + from + "-" + to + "'", period, from, to, counts[i],
                    diffSum[i] / sheets, situation[i]));
        }
        List<TimeBucket> byPeriod = new ArrayList<>();
        for (int period = 1; period <= 2; period++) {
            int first = (period - 1) * perPeriod;
            int[] sum = new int[COLS];
            for (int i = first; i < period * perPeriod; i++) {
                for (int c = 0; c < COLS; c++) {
                    sum[c] += counts[i][c];
                }
            }
            byPeriod.add(bucket(period == 1 ? "1er tiempo" : "2do tiempo", period, 0, periodMinutes, sum,
                    diffSum[first] / sheets, situation[first]));
        }
        unrecordedCards[0] = unrecordedFor;
        unrecordedCards[1] = unrecordedAgainst;
        TimeBucket unrecorded = bucket("Sin minuto registrado", 0, 0, 0, unrecordedCards, 0, new int[3]);
        return new TimeProfile(periodMinutes, played, withSheet, mismatches, buckets, byPeriod, unrecorded,
                shortHanded, powerPlay, new FoulSituation(foulsBySituation[0], foulsBySituation[1], foulsBySituation[2],
                        secondsBySituation[0], secondsBySituation[1], secondsBySituation[2]), shortHandedGoals.result(),
                new ScoreFlow(flow[0], flow[1], flow[2], flow[3], flow[4], flow[5], flow[6], flow[7], flow[8], flow[9],
                        flow[10], flow[11], flow[12], flow[13], flow[14]));
    }

    /**
     * Suma un partido con acta completa al marcador partido a partido (ver {@code flow} en {@link #timeProfile}): si
     * estuvo arriba o abajo en algún momento, quién convirtió primero, cómo terminó y cuántos puntos ganó o perdió en
     * los últimos 5 minutos. Dos goles en el mismo segundo cuentan primero el propio.
     */
    static void scoreFlow(List<Integer> ownGoals, List<Integer> rivalGoals, int resultFor, int resultAgainst,
                          int matchSeconds, int[] flow) {
        List<int[]> goals = new ArrayList<>();
        ownGoals.forEach(t -> goals.add(new int[]{t, 1}));
        rivalGoals.forEach(t -> goals.add(new int[]{t, -1}));
        goals.sort(Comparator.<int[]>comparingInt(g -> g[0]).thenComparingInt(g -> -g[1]));
        int diff = 0;
        boolean led = false;
        boolean trailed = false;
        for (int[] g : goals) {
            diff += g[1];
            led |= diff > 0;
            trailed |= diff < 0;
        }
        int outcome = resultFor > resultAgainst ? 0 : resultFor == resultAgainst ? 1 : 2;
        flow[0]++;
        if (led) {
            flow[1 + outcome]++;
        }
        if (trailed) {
            flow[4 + outcome]++;
        }
        if (!goals.isEmpty()) {
            flow[(goals.getFirst()[1] > 0 ? 7 : 10) + outcome]++;
        }
        // Sobre la hora: los puntos con que terminó contra los que tenía a 5 minutos del final.
        int cutoff = matchSeconds - LATE_SECONDS;
        int before = goals.stream().filter(g -> g[0] < cutoff).mapToInt(g -> g[1]).sum();
        int late = points(resultFor - resultAgainst) - points(before);
        if (late > 0) {
            flow[13] += late;
        } else {
            flow[14] -= late;
        }
    }

    /** Últimos minutos del partido que cuentan como "sobre la hora". */
    static final int LATE_SECONDS = 5 * 60;

    private static int points(int goalDiff) {
        return goalDiff > 0 ? 3 : goalDiff == 0 ? 1 : 0;
    }

    /** 0 = ganando, 1 = empatando, 2 = perdiendo. */
    private static int situationIndex(int goalDiff) {
        return goalDiff > 0 ? 0 : goalDiff == 0 ? 1 : 2;
    }

    /** Suma los segundos que el equipo estuvo ganando, empatando y perdiendo, según el minuto de los goles. */
    static void accumulateSituationSeconds(List<Integer> ownGoals, List<Integer> rivalGoals, int matchSeconds,
                                           int[] seconds) {
        List<int[]> goals = new ArrayList<>();
        ownGoals.forEach(t -> goals.add(new int[]{Math.min(t, matchSeconds), 1}));
        rivalGoals.forEach(t -> goals.add(new int[]{Math.min(t, matchSeconds), -1}));
        goals.sort(Comparator.comparingInt(g -> g[0]));
        int diff = 0;
        int prev = 0;
        for (int[] g : goals) {
            seconds[situationIndex(diff)] += g[0] - prev;
            diff += g[1];
            prev = g[0];
        }
        seconds[situationIndex(diff)] += matchSeconds - prev;
    }

    /**
     * Segundos de sanción: se suma cada sanción, aunque se superponga con otra (dos azules a la vez = 4').
     * La sanción se cumple completa aunque el rival haga un gol. Una sanción del final del 1T sigue en el 2T;
     * al final del partido se corta.
     */
    static int sanctionSeconds(List<int[]> intervals, int matchSeconds) {
        int total = 0;
        for (int[] i : intervals) {
            total += Math.max(0, Math.min(i[1], matchSeconds) - Math.max(i[0], 0));
        }
        return total;
    }

    private static TimeBucket bucket(String label, int period, int from, int to, int[] c,
                                     double avgGoalDiff, int[] situation) {
        return new TimeBucket(label, period, from, to, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9],
                avgGoalDiff, situation[0], situation[1], situation[2]);
    }

    /**
     * ¿El evento es del equipo del informe? Primero por team_id del acta; si falta, por club (logo) y,
     * en un cruce A vs B del mismo club, por el código del equipo.
     */
    static boolean isOwn(MatchEvent ev, TeamRef own, int rivalTeamId, int rivalClubId) {
        if (ev.teamId() == own.teamId()) {
            return true;
        }
        if (ev.teamId() == rivalTeamId) {
            return false;
        }
        if (own.clubId() != rivalClubId && ev.clubId() > 0) {
            return ev.clubId() == own.clubId();
        }
        return ev.teamCode().equalsIgnoreCase(own.code());
    }

    private TimeProfileCalc() {
    }
}
