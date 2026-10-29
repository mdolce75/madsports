package com.madsports.stats;

import com.madsports.sidgad.model.Models;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.TeamRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeProfileCalcTest {

    @Test
    void overlappingSanctionsAddUpAndAreCutAtTheEnd() {
        int match = 2 * 25 * 60;
        // Dos azules simultáneas: se acumulan, 4'.
        assertThat(TimeProfileCalc.sanctionSeconds(List.of(new int[]{600, 720}, new int[]{600, 720}), match)).isEqualTo(240);
        // Superpuestas en parte: 10:00-12:00 y 11:00-13:00 = 2' + 2'.
        assertThat(TimeProfileCalc.sanctionSeconds(List.of(new int[]{600, 720}, new int[]{660, 780}), match)).isEqualTo(240);
        // A 1' del final: solo cuenta ese minuto.
        assertThat(TimeProfileCalc.sanctionSeconds(List.of(new int[]{match - 60, match + 60}), match)).isEqualTo(60);
        // Al final del 1T (24:30) la sanción sigue en el 2T: 2' completos.
        assertThat(TimeProfileCalc.sanctionSeconds(List.of(new int[]{1470, 1590}), match)).isEqualTo(120);
        // Roja: 4'.
        assertThat(TimeProfileCalc.sanctionSeconds(List.of(new int[]{0, TimeProfileCalc.RED_CARD_SECONDS}), match)).isEqualTo(240);
        assertThat(TimeProfileCalc.sanctionSeconds(List.of(), match)).isZero();
    }

    @Test
    void secondsWinningDrawingAndLosing() {
        int[] seconds = new int[3];
        // Gol propio al 10', del rival al 20' y al 30', partido de 50'.
        TimeProfileCalc.accumulateSituationSeconds(List.of(600), List.of(1200, 1800), 3000, seconds);
        assertThat(seconds).containsExactly(600, 600 + 600, 1200);
    }

    @Test
    void inACrossMatchTheEventIsAttributedByCode() {
        TeamRef velezA = new TeamRef(10, 74, "CAVS A", "VELEZ A");
        MatchEvent goalOfB = new MatchEvent(1, 3, 0, Models.EventType.GOAL, 0, 74, "CAVS B", "");
        MatchEvent goalOfA = new MatchEvent(1, 3, 0, Models.EventType.GOAL, 0, 74, "CAVS A", "");

        assertThat(TimeProfileCalc.isOwn(goalOfA, velezA, 11, 74)).isTrue();
        assertThat(TimeProfileCalc.isOwn(goalOfB, velezA, 11, 74)).isFalse();
        // Con team_id en el acta manda el id.
        assertThat(TimeProfileCalc.isOwn(new MatchEvent(1, 3, 0, Models.EventType.BLUE_CARD, 11, 74, "", ""), velezA, 11, 74))
                .isFalse();
    }

    @Test
    void scoreFlowOfAWinDecidedOnTheEnd() {
        int[] flow = new int[15];
        // 1-0 al 10', 1-1 al 20' y 2-1 a 100" del final (partido de 50').
        TimeProfileCalc.scoreFlow(List.of(600, 2900), List.of(1200), 2, 1, 3000, flow);
        assertThat(flow[0]).isEqualTo(1);    // un partido con acta completa
        assertThat(flow[1]).isEqualTo(1);    // estuvo arriba y ganó
        assertThat(flow[4] + flow[5] + flow[6]).isZero();   // nunca estuvo abajo
        assertThat(flow[7]).isEqualTo(1);    // convirtió primero y ganó
        assertThat(flow[13]).isEqualTo(2);   // de empatar a ganar sobre la hora: +2 puntos
        assertThat(flow[14]).isZero();
    }

    @Test
    void scoreFlowOfALossWithoutLeading() {
        int[] flow = new int[15];
        TimeProfileCalc.scoreFlow(List.of(), List.of(300), 0, 1, 3000, flow);
        assertThat(flow[6]).isEqualTo(1);    // estuvo abajo y perdió
        assertThat(flow[12]).isEqualTo(1);   // le convirtieron primero y perdió
        assertThat(flow[1] + flow[2] + flow[3]).isZero();
        assertThat(flow[13] + flow[14]).isZero();   // ya perdía antes de los últimos 5'
    }
}
