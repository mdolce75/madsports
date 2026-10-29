package com.madsports.stats;

import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.StandingRow;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.stats.ReportData.RoundPosition;
import com.madsports.stats.StatsEngine.CompetitionData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StandingsCalcTest {

    static Match match(int idp, int round, int home, String homeName, int away, String awayName, Integer hg, Integer ag) {
        return new Match(idp, round, LocalDate.of(2026, 3, round), null, "", home, home, homeName, away, away, awayName,
                hg, ag);
    }

    static StandingRow row(int position, int clubId, String name) {
        return new StandingRow("A", position, clubId, name, name, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void positionAfterEachRoundItPlayed() {
        List<Match> matches = List.of(
                match(1, 1, 1, "UNO", 2, "DOS", 3, 1),
                match(2, 2, 3, "TRES", 1, "UNO", 2, 0),
                match(3, 3, 2, "DOS", 3, "TRES", 1, 1),     // fecha en que UNO no juega
                match(4, 4, 1, "UNO", 3, "TRES", null, null)); // sin jugar
        CompetitionData cd = new CompetitionData(null, List.of(row(1, 1, "UNO"), row(2, 2, "DOS"), row(3, 3, "TRES")),
                null, matches, List.of(), Map.of());

        // Fecha 1: UNO gana 3-1 y queda 1º. Fecha 2: pierde 0-2 con TRES, que lo pasa por diferencia de gol.
        assertThat(StandingsCalc.evolution(cd, new TeamRef(1, 1, "UNO", "UNO"), "A"))
                .containsExactly(new RoundPosition(1, 1), new RoundPosition(2, 2));
    }
}
