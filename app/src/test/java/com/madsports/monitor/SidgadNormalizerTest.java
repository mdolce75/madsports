package com.madsports.monitor;

import com.madsports.Fixtures;
import com.madsports.sidgad.tables.SidgadCompetition;
import com.madsports.sidgad.tables.SidgadCompetitionRepository;
import com.madsports.sidgad.tables.SidgadMatch;
import com.madsports.sidgad.tables.SidgadMatchRepository;
import com.madsports.sidgad.tables.SidgadNormalizer;
import com.madsports.sidgad.tables.SidgadTeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** El HTML guardado de SIDGAD, pasado a las tablas propias (con la migración V34 de verdad): catálogo y calendarios. */
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
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
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
}
