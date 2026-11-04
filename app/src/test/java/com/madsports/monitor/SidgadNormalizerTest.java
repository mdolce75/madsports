package com.madsports.monitor;

import com.madsports.Fixtures;
import com.madsports.sidgad.tables.SidgadCompetition;
import com.madsports.sidgad.tables.SidgadCompetitionRepository;
import com.madsports.sidgad.tables.SidgadMatch;
import com.madsports.sidgad.tables.SidgadMatchEvent;
import com.madsports.sidgad.tables.SidgadMatchEventRepository;
import com.madsports.sidgad.tables.SidgadMatchLineup;
import com.madsports.sidgad.tables.SidgadMatchLineupRepository;
import com.madsports.sidgad.tables.SidgadMatchRepository;
import com.madsports.sidgad.tables.SidgadNormalizer;
import com.madsports.sidgad.tables.SidgadPlayerRepository;
import com.madsports.sidgad.tables.SidgadPlayerSeasonMatch;
import com.madsports.sidgad.tables.SidgadPlayerSeasonMatchRepository;
import com.madsports.sidgad.tables.SidgadPlayerStats;
import com.madsports.sidgad.tables.SidgadPlayerStatsRepository;
import com.madsports.sidgad.tables.SidgadStanding;
import com.madsports.sidgad.tables.SidgadStandingRepository;
import com.madsports.sidgad.tables.SidgadTeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El HTML guardado de SIDGAD, pasado a las tablas propias (con las migraciones V34 a V36 de verdad): catálogo,
 * calendarios, actas, tablas de posiciones, estadísticas por jugador y fichas de temporada.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tablas;DB_CLOSE_DELAY=-1")
class SidgadNormalizerTest {

    @Autowired
    SidgadNormalizer normalizer;

    @Autowired
    SidgadCompetitionRepository competitions;

    @Autowired
    SidgadTeamRepository teams;

    @Autowired
    SidgadMatchRepository matches;

    @Autowired
    SidgadPlayerRepository players;

    @Autowired
    SidgadMatchLineupRepository lineups;

    @Autowired
    SidgadMatchEventRepository events;

    @Autowired
    SidgadStandingRepository standings;

    @Autowired
    SidgadPlayerStatsRepository stats;

    @Autowired
    SidgadPlayerSeasonMatchRepository seasons;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        standings.deleteAll();
        stats.deleteAll();
        seasons.deleteAll();
        lineups.deleteAll();
        events.deleteAll();
        players.deleteAll();
        matches.deleteAll();
        teams.deleteAll();
        competitions.deleteAll();
    }

    void loadAll() {
        normalizer.catalog(Fixtures.read("catalogo_2026.html"), Fixtures.read("portal.html"));
        normalizer.calendar(779, Fixtures.read("cal_779.html"));
    }

    @Test
    void theCatalogBecomesCompetitionsWithTheirTeams() {
        int n = normalizer.catalog(Fixtures.read("catalogo_2026.html"), Fixtures.read("portal.html"));

        assertThat(n).isPositive();
        assertThat(competitions.count()).isEqualTo(n);
        SidgadCompetition primera = competitions.findById(779).orElseThrow();
        assertThat(primera.getName()).isEqualTo("TORNEO CLAUSURA PRIMERA MASCULINO (FPP)");
        assertThat(primera.getFederation()).isEqualTo("FPP");
        assertThat(primera.getSeasonYear()).isEqualTo(2026);
        assertThat(primera.getTournament()).isEqualTo("CLOSING");
        assertThat(primera.getLevel()).isEqualTo("PRIMERA");
        assertThat(primera.getBranch()).isEqualTo("MASCULINO");
        assertThat(teams.findByCompetitionId(779)).anySatisfy(t -> {
            assertThat(t.getClubId()).isEqualTo(74);
            assertThat(t.getName()).isEqualTo("VELEZ");
        });
    }

    @Test
    void theCalendarBecomesMatchesWithTheirResults() {
        normalizer.catalog(Fixtures.read("catalogo_2026.html"), Fixtures.read("portal.html"));

        int n = normalizer.calendar(779, Fixtures.read("cal_779.html"));

        List<SidgadMatch> rows = matches.findByCompetitionIdOrderByRoundAscMatchDateAsc(779);
        assertThat(rows).hasSize(n).isNotEmpty();
        // Vélez 2-4 Argentinos (acta 26390).
        assertThat(rows).anySatisfy(m -> {
            assertThat(m.getIdp()).isEqualTo(26390);
            assertThat(m.isPlayed()).isTrue();
            assertThat(m.getHomeGoals() + m.getAwayGoals()).isEqualTo(6);
        });
        // Los que todavía no se jugaron: sin goles.
        assertThat(rows).filteredOn(m -> !m.isPlayed()).allSatisfy(m -> assertThat(m.getHomeGoals()).isNull());
    }

    @Test
    void normalizingAgainLeavesTheSameRows() {
        loadAll();
        long c = competitions.count(), t = teams.count(), m = matches.count();

        loadAll();

        assertThat(competitions.count()).isEqualTo(c);
        assertThat(teams.count()).isEqualTo(t);
        assertThat(matches.count()).isEqualTo(m);
    }

    @Test
    void aCalendarOfAnUnknownCompetitionIsSkipped() {
        assertThat(normalizer.calendar(999_999, Fixtures.read("cal_779.html"))).isZero();
        assertThat(matches.count()).isZero();
    }

    @Test
    void theCategoriesOfAClubAreASqlQuery() {
        normalizer.catalog(Fixtures.read("catalogo_2026.html"), Fixtures.read("portal.html"));

        // Petroleros (AMP, club 98) en 2026: lo que antes obligaba a descomprimir y parsear el HTML.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select distinct c.level, c.branch from sidgad_team t
                join sidgad_competition c on c.id = t.competition_id
                where t.club_id = 98 and c.season_year = 2026 and c.level is not null
                order by c.branch, c.level""");

        assertThat(rows).extracting(r -> r.get("BRANCH") + " " + r.get("LEVEL"))
                .contains("MASCULINO SENIOR", "FEMENINO JUVENIL", "FEMENINO INFANTIL");
    }

    @Test
    void aSheetBecomesTheLineupOfEachTeamAndItsPlayers() {
        int n = normalizer.sheet(26390, Fixtures.read("gr_26390.html"));

        // Vélez (local) 2-4 Argentinos Juniors: 9 citados por equipo.
        List<SidgadMatchLineup> rows = lineups.findByIdp(26390);
        assertThat(rows).hasSize(n).hasSize(18);
        assertThat(rows).filteredOn(SidgadMatchLineup::isHome).hasSize(9)
                .allSatisfy(r -> assertThat(r.getTeamName()).isEqualTo("VELEZ"));
        assertThat(rows).filteredOn(r -> r.getPlayerId() == 3851).singleElement().satisfies(r -> {
            assertThat(r.getGoals()).isEqualTo(2);
            assertThat(r.isStarter()).isTrue();
            assertThat(r.getShirtNumber()).isEqualTo("07");
        });
        assertThat(rows).filteredOn(SidgadMatchLineup::isGoalkeeper).extracting(SidgadMatchLineup::getPlayerId)
                .containsExactlyInAnyOrder(5888, 2684);
        // Cada jugador, una vez, con su nombre.
        assertThat(players.count()).isEqualTo(18);
        assertThat(players.findById(3851)).get().satisfies(p -> {
            assertThat(p.getName()).isEqualTo("Ignacio J Graiño");
            assertThat(p.getSurname()).isEqualTo("Graiño");
        });
    }

    @Test
    void aSheetBecomesItsEvents() {
        normalizer.sheet(26389, Fixtures.read("gr_26389.html"));

        // Huracán 13-2 GEBA B: 15 goles, 4 azules, 2 amarillas y 14 faltas de equipo.
        List<SidgadMatchEvent> rows = events.findByIdp(26389);
        assertThat(rows).hasSize(35);
        assertThat(rows).filteredOn(e -> e.getEventType().equals("GOAL")).hasSize(15);
        assertThat(rows).filteredOn(e -> e.getEventType().equals("BLUE_CARD")).hasSize(4);
        assertThat(rows).filteredOn(e -> e.getEventType().equals("FOUL")).hasSize(14);
        assertThat(rows).allSatisfy(e -> assertThat(e.getPeriod()).isPositive());
    }

    @Test
    void normalizingASheetAgainReplacesItAndAnUnpublishedOneLeavesItEmpty() {
        normalizer.sheet(26390, Fixtures.read("gr_26390.html"));
        normalizer.sheet(26390, Fixtures.read("gr_26390.html"));
        assertThat(lineups.findByIdp(26390)).hasSize(18);
        assertThat(players.count()).isEqualTo(18);

        // SIDGAD no la publicó (o la sacó): el partido queda sin planilla ni eventos; los jugadores quedan.
        assertThat(normalizer.sheet(26390, Fixtures.read("gr_sin_acta.html"))).isZero();
        assertThat(lineups.findByIdp(26390)).isEmpty();
        assertThat(events.findByIdp(26390)).isEmpty();
        assertThat(players.count()).isEqualTo(18);
    }

    @Test
    void theScorersOfACompetitionAreASqlQuery() {
        loadAll();
        normalizer.sheet(26390, Fixtures.read("gr_26390.html"));

        // Goleadores de la 779 con las actas normalizadas: une partidos y planillas por idp.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.name, sum(l.goals) goals from sidgad_match m
                join sidgad_match_lineup l on l.idp = m.idp
                join sidgad_player p on p.id = l.player_id
                where m.competition_id = 779
                group by p.name having sum(l.goals) > 0
                order by goals desc, p.name""");

        // Vélez 2-4 Argentinos: los 6 goles del acta, 2 de Graiño.
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.get("NAME")).isEqualTo("Ignacio J Graiño");
            assertThat(((Number) r.get("GOALS")).intValue()).isEqualTo(2);
        });
        assertThat(rows.stream().mapToInt(r -> ((Number) r.get("GOALS")).intValue()).sum()).isEqualTo(6);
    }

    @Test
    void theStandingsOfACompetitionByGroup() {
        normalizer.catalog(Fixtures.read("catalogo_2026.html"), Fixtures.read("portal.html"));

        int n = normalizer.standings(779, Fixtures.read("clasif_779.html"));
        normalizer.standings(779, Fixtures.read("clasif_779.html"));

        List<SidgadStanding> rows = standings.findByCompetitionIdOrderByGroupNameAscPositionAsc(779);
        assertThat(rows).hasSize(n).isNotEmpty();
        assertThat(rows).extracting(SidgadStanding::getGroupName).containsOnly("IMPAR", "PAR");
        assertThat(rows).filteredOn(r -> "CAVS".equals(r.getCode())).singleElement().satisfies(r -> {
            assertThat(r.getClubId()).isEqualTo(74);
            assertThat(r.getName()).isEqualTo("VELEZ");
        });
        assertThat(normalizer.standings(999_999, Fixtures.read("clasif_779.html"))).isZero();
    }

    @Test
    void theStatsByPlayerOfACompetition() {
        normalizer.catalog(Fixtures.read("catalogo_2026.html"), Fixtures.read("portal.html"));

        int n = normalizer.playerStats(765, Fixtures.read("stats_765.html"));
        normalizer.playerStats(765, Fixtures.read("stats_765.html"));

        List<SidgadPlayerStats> rows = stats.findByCompetitionIdOrderByGoalsDesc(765);
        assertThat(rows).hasSize(n).isNotEmpty();
        assertThat(rows.getFirst()).satisfies(top -> {
            assertThat(top.getName()).isEqualTo("Martina Lorena Granato");
            assertThat(top.getTeamCode()).isEqualTo("HARRO");
            assertThat(top.getGoals()).isEqualTo(53);
            assertThat(top.getGames()).isEqualTo(7);
            assertThat(top.getPenaltiesTaken()).isEqualTo(1);
        });
    }

    @Test
    void theSeasonProfileOfAPlayer() {
        int n = normalizer.playerSeason(5037, 40, Fixtures.read("profileseason_5037.html"));
        normalizer.playerSeason(5037, 40, Fixtures.read("profileseason_5037.html"));

        List<SidgadPlayerSeasonMatch> rows = seasons.findByPlayerIdAndSeasonIdOrderByMatchDateAsc(5037, 40);
        assertThat(rows).hasSize(n).hasSize(37);
        List<SidgadPlayerSeasonMatch> juvenil = rows.stream()
                .filter(m -> m.getCompetitionName().equals("TORNEO APERTURA JUVENIL MASCULINO (FPP)")).toList();
        assertThat(juvenil).hasSize(19);
        assertThat(juvenil.stream().mapToInt(SidgadPlayerSeasonMatch::getGoals).sum()).isEqualTo(14);
        // Vélez 11-1 Municipalidad: sin acta publicada, pero en la ficha, con sus 3 goles.
        assertThat(rows).filteredOn(m -> Integer.valueOf(23758).equals(m.getIdp())).singleElement().satisfies(m -> {
            assertThat(m.getHomeName()).isEqualTo("CAVS");
            assertThat(m.getHomeGoals()).isEqualTo(11);
            assertThat(m.getGoals()).isEqualTo(3);
        });
    }
}
