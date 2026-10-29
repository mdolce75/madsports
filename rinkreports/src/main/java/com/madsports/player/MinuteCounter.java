package com.madsports.player;

import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.MatchEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuenta goles y tarjetas del jugador por tramo de 5 minutos con los eventos del acta. Lo que la planilla le anota
 * y no tiene evento con minuto (o es de fuera del juego, período 11) va a "sin minuto registrado".
 */
final class MinuteCounter {

    static final int BUCKET_MINUTES = 5;

    private final int periodMinutes;
    private final int perPeriod;
    private final int[][] counts;
    private final int[] unrecorded = new int[4];

    int periodMinutes() {
        return periodMinutes;
    }

    MinuteCounter(int periodMinutes) {
        this.periodMinutes = periodMinutes;
        this.perPeriod = (periodMinutes + BUCKET_MINUTES - 1) / BUCKET_MINUTES;
        this.counts = new int[2 * perPeriod][4];
    }

    void add(LineupPlayer sheet, List<MatchEvent> events) {
        int[] withMinute = new int[4];
        for (MatchEvent e : events) {
            int column = switch (e.type()) {
                case GOAL -> 0;
                case YELLOW_CARD -> 1;
                case BLUE_CARD -> 2;
                case RED_CARD -> 3;
                case FOUL -> -1;
            };
            if (column < 0) {
                continue;
            }
            int period = e.period() == PlayerReportAssembler.HALFTIME_PERIOD ? 2 : e.period();
            int minute = e.period() == PlayerReportAssembler.HALFTIME_PERIOD ? 0 : e.minute();
            if (period < 1 || period > 2) {
                continue;   // fuera del juego: queda como "sin minuto"
            }
            counts[(period - 1) * perPeriod + Math.min(minute / BUCKET_MINUTES, perPeriod - 1)][column]++;
            withMinute[column]++;
        }
        int[] inSheet = {sheet.goals(), sheet.yellowCards(), sheet.blueCards(), sheet.redCards()};
        for (int c = 0; c < 4; c++) {
            unrecorded[c] += Math.max(0, inSheet[c] - withMinute[c]);
        }
    }

    PlayerReport.Minutes result() {
        List<PlayerReport.Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            int from = (i % perPeriod) * BUCKET_MINUTES;
            int to = Math.min(from + BUCKET_MINUTES, periodMinutes);
            String label = (i < perPeriod ? "1T " : "2T ") + from + "-" + to + "'";
            buckets.add(new PlayerReport.Bucket(label, counts[i][0], counts[i][1], counts[i][2], counts[i][3]));
        }
        return new PlayerReport.Minutes(periodMinutes, buckets, new PlayerReport.Bucket("Sin minuto registrado",
                unrecorded[0], unrecorded[1], unrecorded[2], unrecorded[3]));
    }
}
