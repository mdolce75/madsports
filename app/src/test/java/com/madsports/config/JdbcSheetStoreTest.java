package com.madsports.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Actas guardadas en la base (con la migración V29 de verdad), comprimidas, y cuándo se vuelven a bajar. */
class JdbcSheetStoreTest {

    /** Un reloj que se puede adelantar. */
    static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    JdbcTemplate jdbc;
    MovableClock clock;
    JdbcSheetStore store;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:actas" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/h2/V29__actas_de_sidgad.sql"),
                StandardCharsets.UTF_8));
        clock = new MovableClock();
        store = new JdbcSheetStore(jdbc, clock);
    }

    @Test
    void savedCompressedAndReadBack() {
        String html = "<table>" + "<tr><td>Gol</td></tr>".repeat(5000) + "</table> ñandú";
        store.put(21391, html);
        assertThat(store.get(21391)).contains(html);
        assertThat(store.get(1)).isEmpty();
        // Comprimida: mucho menos que el HTML.
        byte[] gz = jdbc.queryForObject("select html_gz from sidgad_game_sheet where idp = 21391", byte[].class);
        assertThat(gz.length).isLessThan(html.length() / 10);
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void inItsFirstDaysItIsRecheckedDailyAndThenItStaysFixed() {
        store.put(5, "v1");
        clock.now = clock.now.plus(Duration.ofHours(23));
        assertThat(store.get(5)).contains("v1");
        // Más de 24 h y todavía en la primera semana: se vuelve a bajar (SIDGAD puede haberla corregido).
        clock.now = clock.now.plus(Duration.ofHours(2));
        assertThat(store.get(5)).isEmpty();
        store.put(5, "v2");
        assertThat(store.get(5)).contains("v2");
        // Pasada la semana desde que se bajó la primera vez: queda fija, aunque la última lectura sea vieja.
        clock.now = clock.now.plus(Duration.ofDays(30));
        assertThat(store.get(5)).contains("v2");
        // Volver a guardarla conserva la fecha de la primera vez.
        store.put(5, "v3");
        assertThat(jdbc.queryForObject("select first_fetched_at from sidgad_game_sheet where idp = 5",
                java.sql.Timestamp.class).toInstant()).isEqualTo(Instant.parse("2026-10-20T12:00:00Z"));
    }
}
