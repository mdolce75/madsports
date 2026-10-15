package com.rinkmetrics.report;

import com.rinkmetrics.sidgad.model.Competition;
import com.rinkmetrics.sidgad.model.Models.PlayerStats;
import com.rinkmetrics.sidgad.model.Models.SeasonMatch;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonScorersTest {

    static final Competition APERTURA = Competition.of(696, "TORNEO APERTURA JUNIORS FEMENINO (FPP)", 3, 40, List.of(), 25);

    static SeasonMatch partido(String competition, int day, int goals, int blue) {
        return new SeasonMatch(competition, LocalDate.of(2026, 5, day), "CAVS", "GEBA", 3, 1, 1000 + day, goals, 0,
                0, 1, 1, 1, blue, 0);
    }

    @Test
    void lasGoleadorasSalenDeLaFichaDeTemporadaConLosPartidosSinActa() {
        // La tabla de SIDGAD solo cuenta el partido con acta (1 gol); la ficha tiene los 3 partidos (5 goles).
        List<PlayerStats> table = List.of(new PlayerStats(7, 4889, "CAVS", 74, "Luciana Barilla", 1, 1, 0, 0, 0, 0, 0, 0, 0));
        Map<Integer, List<SeasonMatch>> profiles = Map.of(7, List.of(
                partido("TORNEO APERTURA JUNIORS FEMENINO (FPP)", 2, 1, 0),
                partido("TORNEO APERTURA JUNIORS FEMENINO (FPP)", 9, 3, 1),
                partido("TORNEO APERTURA JUNIORS FEMENINO (FPP)", 16, 1, 0),
                // Otra competición: no suma acá, aunque el nombre empiece igual.
                partido("TORNEO APERTURA JUNIORS FEMENINO 2 (FPP)", 23, 4, 0)));

        List<PlayerStats> out = SeasonScorers.forCompetition(APERTURA, 74, Map.of(7, 4889), Map.of(7, "Luciana Barilla"),
                profiles, table);

        assertThat(out).singleElement().satisfies(p -> {
            assertThat(p.teamId()).isEqualTo(4889);
            assertThat(p.teamCode()).isEqualTo("CAVS");
            assertThat(p.games()).isEqualTo(3);
            assertThat(p.goals()).isEqualTo(5);
            assertThat(p.blueCards()).isEqualTo(1);
            assertThat(p.penaltiesScored()).isEqualTo(3);
            assertThat(p.penaltiesTaken()).isEqualTo(3);
        });
    }

    @Test
    void sinFichaQuedaLaFilaDeLaTablaDeSidgad() {
        List<PlayerStats> table = List.of(new PlayerStats(8, 4889, "CAVS", 74, "Martina Piluso", 2, 1, 0, 0, 0, 0, 0, 0, 0));

        assertThat(SeasonScorers.forCompetition(APERTURA, 74, Map.of(8, 4889), Map.of(), Map.of(), table))
                .containsExactlyElementsOf(table);
        // Sin ficha ni fila en la tabla, no aparece.
        assertThat(SeasonScorers.forCompetition(APERTURA, 74, Map.of(9, 4889), Map.of(), Map.of(), table)).isEmpty();
    }

    @Test
    void laCompeticionSeComparaEntera() {
        assertThat(SeasonScorers.sameCompetition("torneo apertura  juniors femenino (FPP)",
                "TORNEO APERTURA JUNIORS FEMENINO (FPP)")).isTrue();
        assertThat(SeasonScorers.sameCompetition("TORNEO APERTURA JUNIORS FEMENINO 2 (FPP)",
                "TORNEO APERTURA JUNIORS FEMENINO (FPP)")).isFalse();
    }
}
