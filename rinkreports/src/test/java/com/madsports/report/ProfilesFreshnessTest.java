package com.madsports.report;

import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.stats.StatsEngine.CompetitionData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Desde cuándo sirve una ficha de temporada guardada en el informe del club. */
class ProfilesFreshnessTest {

    static final ClubView CLUB = new ClubView(74, "CAVS", "VELEZ", List.of());

    static CompetitionData data(Match... matches) {
        Competition c = new Competition(1, "TORNEO APERTURA PRIMERA MASCULINO", 3, 40, Competition.Tournament.OPENING,
                new Category("PRIMERA", "MASCULINO"), 1,
                List.of(new TeamRef(10, 74, "CAVS", "VELEZ"), new TeamRef(20, 72, "HAR", "HARRODS"),
                        new TeamRef(30, 22, "HUR", "HURACAN")), 25);
        return new CompetitionData(c, List.of(), null, List.of(matches), List.of(), new HashMap<>(), Set.of(), new HashMap<>());
    }

    static Match match(int idp, String date, int home, int away, Integer goals) {
        return new Match(idp, 1, LocalDate.parse(date), null, "", home, 0, "", away, 0, "", goals, goals);
    }

    @Test
    void afterTheDayOfTheClubsLastMatch() {
        Instant now = Instant.parse("2026-10-20T15:00:00Z");
        List<CompetitionData> data = List.of(data(
                match(1, "2026-10-10", 10, 20, 2),
                match(2, "2026-10-17", 30, 10, 1),     // su último partido jugado: sábado 17
                match(3, "2026-10-19", 20, 30, 3),     // de otros equipos: no cuenta
                match(4, "2026-10-24", 10, 30, null))); // todavía no se jugó
        // Sirve la ficha bajada desde el domingo 18 a las 0 (hora argentina).
        assertThat(ReportService.profilesNotBefore(CLUB, data, now)).isEqualTo(Instant.parse("2026-10-18T03:00:00Z"));
    }

    @Test
    void ifTheClubPlayedTodayTheLastTwoHoursAndWithoutMatchesTheLastDay() {
        Instant now = Instant.parse("2026-10-20T15:00:00Z");
        assertThat(ReportService.profilesNotBefore(CLUB, List.of(data(match(1, "2026-10-20", 10, 20, 1))), now))
                .isEqualTo(now.minus(Duration.ofHours(2)));
        assertThat(ReportService.profilesNotBefore(CLUB, List.of(data()), now)).isEqualTo(now.minus(Duration.ofHours(24)));
    }
}
