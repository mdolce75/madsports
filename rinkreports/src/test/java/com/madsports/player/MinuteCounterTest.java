package com.madsports.player;

import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.MatchEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MinuteCounterTest {

    @Test
    void cardsByColorAndBucket() {
        MinuteCounter counter = new MinuteCounter(20);
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
    void theLastBucketEndsWithThePeriod() {
        MinuteCounter counter = new MinuteCounter(22);
        LineupPlayer sheet = new LineupPlayer(7, "X", true, "7", "Jugador", true, 2, 0, 0, 0);
        // Un gol en el minuto 21 cae en el último tramo (20-22'); el otro no tiene evento.
        counter.add(sheet, List.of(new MatchEvent(1, 21, 30, EventType.GOAL, 0, 0, "", "", 7)));
        PlayerReport.Minutes mn = counter.result();

        assertThat(mn.periodMinutes()).isEqualTo(22);
        assertThat(mn.buckets()).hasSize(10);
        assertThat(mn.buckets().get(4)).isEqualTo(new PlayerReport.Bucket("1T 20-22'", 1, 0, 0, 0));
        assertThat(mn.buckets().get(9).label()).isEqualTo("2T 20-22'");
        assertThat(mn.unrecorded()).isEqualTo(new PlayerReport.Bucket("Sin minuto registrado", 1, 0, 0, 0));
    }
}
