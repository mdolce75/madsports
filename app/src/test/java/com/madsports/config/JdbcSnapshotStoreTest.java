package com.madsports.config;

import com.madsports.sidgad.SnapshotStore.Stored;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las copias de SIDGAD guardadas en la base (con las migraciones V32 y V33 de verdad): comprimidas, con la hora de la
 * bajada y la del último cambio.
 */
class JdbcSnapshotStoreTest {

    JdbcTemplate jdbc;
    JdbcSheetStoreTest.MovableClock clock;
    JdbcSnapshotStore store;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:copias" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        migrate("V32__catalogo_guardado.sql");
        // Una copia del catálogo de antes de V33: sigue estando, con su hora como la del último cambio.
        jdbc.update("insert into source_catalog (catalog_key, body_gz, fetched_at) values (?, ?, ?)", "sidgad:portal",
                JdbcSheetStore.gzip("portal"), Timestamp.from(Instant.parse("2026-11-01T08:00:00Z")));
        migrate("V33__copias_de_sidgad.sql");
        clock = new JdbcSheetStoreTest.MovableClock();
        store = new JdbcSnapshotStore(jdbc, clock);
    }

    private void migrate(String file) throws IOException {
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/h2/" + file), StandardCharsets.UTF_8));
    }

    @Test
    void theCatalogCopiesOfBeforeAreKept() {
        Instant at = Instant.parse("2026-11-01T08:00:00Z");
        assertThat(store.get("sidgad:portal")).contains(new Stored("portal", at, at));
    }

    @Test
    void savedCompressedWithWhenItWasDownloadedAndWhenItChanged() {
        String html = "<table>Vélez</table>".repeat(20_000);
        assertThat(store.put("sidgad:standings:779", html)).isTrue();
        Instant first = clock.now;
        assertThat(store.get("sidgad:standings:779")).contains(new Stored(html, first, first));
        assertThat(store.get("sidgad:calendar:779")).isEmpty();
        byte[] gz = jdbc.queryForObject("select body_gz from source_snapshot where snapshot_key = 'sidgad:standings:779'",
                byte[].class);
        assertThat(gz.length).isLessThan(html.length() / 10);

        // Igual al día siguiente: solo cambia la hora de la bajada.
        clock.now = clock.now.plus(Duration.ofDays(1));
        assertThat(store.put("sidgad:standings:779", html)).isFalse();
        assertThat(store.get("sidgad:standings:779")).contains(new Stored(html, clock.now, first));

        // Distinto: cambian las dos.
        clock.now = clock.now.plus(Duration.ofDays(1));
        assertThat(store.put("sidgad:standings:779", "v2")).isTrue();
        assertThat(store.get("sidgad:standings:779")).contains(new Stored("v2", clock.now, clock.now));
        assertThat(store.count()).isEqualTo(2);
    }
}
