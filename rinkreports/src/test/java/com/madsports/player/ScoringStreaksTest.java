package com.madsports.player;

import com.madsports.player.PlayerReport.MatchRow;
import com.madsports.player.PlayerReport.ScoringStreaks;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringStreaksTest {

    /** Una fila de partido con solo lo que mira la racha: la fecha y los goles del jugador. */
    private static MatchRow row(int day, int goals) {
        return new MatchRow(LocalDate.of(2026, 3, day), "PRIMERA", "Apertura", "Rival", true, 0, 0, "G", true, goals,
                0, 0, 0);
    }

    @Test
    void theCurrentStreakCountsTheConsecutiveScoringMatchesUpToTheLast() {
        // Convirtió en los últimos tres partidos seguidos (el del medio sin gol cortó una racha anterior).
        ScoringStreaks s = ScoringStreaks.of(List.of(row(1, 1), row(2, 0), row(3, 2), row(4, 1), row(5, 1)));
        assertThat(s.current()).isEqualTo(3);
        assertThat(s.longest()).isEqualTo(3);
    }

    @Test
    void whenTheLastMatchHadNoGoalTheCurrentStreakIsZero() {
        ScoringStreaks s = ScoringStreaks.of(List.of(row(1, 1), row(2, 2), row(3, 1), row(4, 0)));
        assertThat(s.current()).isZero();
        assertThat(s.longest()).isEqualTo(3);
    }

    @Test
    void theOrderIsByDateNotByPositionInTheList() {
        // Desordenados: el último por fecha (día 5) no convirtió, así que no hay racha abierta.
        ScoringStreaks s = ScoringStreaks.of(List.of(row(5, 0), row(1, 1), row(4, 2), row(2, 1), row(3, 1)));
        assertThat(s.current()).isZero();
        assertThat(s.longest()).isEqualTo(4);   // días 1 a 4
    }

    @Test
    void bracesAndHatTricksAreCountedToo() {
        ScoringStreaks s = ScoringStreaks.of(List.of(row(1, 2), row(2, 3), row(3, 1), row(4, 4)));
        assertThat(s.current()).isEqualTo(4);
        assertThat(s.braces()).isEqualTo(1);
        assertThat(s.hatTricks()).isEqualTo(2);
    }
}
