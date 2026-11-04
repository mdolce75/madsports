package com.madsports.monitor;

import com.madsports.Fixtures;
import com.madsports.sidgad.SheetStore;
import com.madsports.sidgad.SnapshotStore;
import com.madsports.sidgad.tables.SidgadTablesLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Lo guardado de SIDGAD, llevado a las tablas propias: la carga inicial y después solo lo que cambió. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cargatablas;DB_CLOSE_DELAY=-1")
class SidgadTablesLoaderTest {

    @Autowired
    SidgadTablesLoader loader;

    @Autowired
    SnapshotStore snapshots;

    @Autowired
    SheetStore sheets;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @BeforeEach
    void clean() {
        for (String t : new String[] {"sidgad_player_season_match", "sidgad_player_stats", "sidgad_standing",
                "sidgad_match_event", "sidgad_match_lineup", "sidgad_player", "sidgad_match", "sidgad_team",
                "sidgad_competition", "sidgad_game_sheet", "source_snapshot"}) {
            jdbc.update("delete from " + t);
        }
    }

    /**
     * Espera a que el reloj avance: en Windows da saltos de milisegundos, y lo guardado en el mismo instante en que
     * empieza una carga lo vuelve a tomar la siguiente (a propósito: así no se pierde nada).
     */
    void tick() {
        Instant now = clock.instant();
        while (!clock.instant().isAfter(now.plusMillis(1))) {
            Thread.onSpinWait();
        }
    }

    int rows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    void saveAll() {
        snapshots.put("sidgad:ls", Fixtures.read("catalogo_2026.html"));
        snapshots.put("sidgad:portal", Fixtures.read("portal.html"));
        snapshots.put("sidgad:calendar:779", Fixtures.read("cal_779.html"));
        snapshots.put("sidgad:standings:779", Fixtures.read("clasif_779.html"));
        snapshots.put("sidgad:stats:765", Fixtures.read("stats_765.html"));
        snapshots.put("sidgad:profile:5037:40", Fixtures.read("profileseason_5037.html"));
        // Lo que no son datos (escudos, marcas de la sincronización) no va a las tablas.
        snapshots.put("sidgad:sync:FPP", "2026-11-04T06:00:00Z");
        sheets.put(26390, Fixtures.read("gr_26390.html"));
        sheets.put(26389, Fixtures.read("gr_26389.html"));
    }

    @Test
    void theFirstLoadTakesEverythingSavedAndTheNextOnlyWhatChanged() {
        saveAll();
        tick();

        SidgadTablesLoader.Load first = loader.catchUp().orElseThrow();

        assertThat(first.errors()).isZero();
        assertThat(first.competitions()).isPositive();
        assertThat(first.fragments()).isEqualTo(3);
        assertThat(first.sheets()).isEqualTo(2);
        assertThat(first.profiles()).isEqualTo(1);
        assertThat(rows("sidgad_competition")).isEqualTo(first.competitions());
        assertThat(rows("sidgad_match")).isPositive();
        assertThat(rows("sidgad_standing")).isPositive();
        assertThat(rows("sidgad_player_stats")).isPositive();
        assertThat(rows("sidgad_match_lineup")).isEqualTo(18 + jdbc.queryForObject(
                "select count(*) from sidgad_match_lineup where idp = 26389", Integer.class));
        assertThat(rows("sidgad_player_season_match")).isEqualTo(37);
        assertThat(first.summary()).startsWith("Tablas propias: ").contains("2 actas; 1 fichas.");

        // Sin cambios: nada para hacer.
        SidgadTablesLoader.Load second = loader.catchUp().orElseThrow();
        assertThat(second.competitions() + second.fragments() + second.sheets() + second.profiles()).isZero();

        // Cambia un calendario y se vuelve a bajar un acta: solo eso.
        snapshots.put("sidgad:calendar:779", Fixtures.read("cal_765.html"));
        sheets.put(26390, Fixtures.read("gr_26390.html"));
        tick();
        SidgadTablesLoader.Load third = loader.catchUp().orElseThrow();
        assertThat(third.competitions()).isZero();
        assertThat(third.fragments()).isEqualTo(1);
        assertThat(third.sheets()).isEqualTo(1);
        assertThat(third.profiles()).isZero();
    }
}
