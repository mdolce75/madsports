package com.madsports.config;

import com.madsports.sidgad.CatalogStore.Stored;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** La copia del catálogo guardada en la base (con la migración V32 de verdad), comprimida y con su hora. */
class JdbcCatalogStoreTest {

    JdbcTemplate jdbc;
    JdbcSheetStoreTest.MovableClock clock;
    JdbcCatalogStore store;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:catalogo" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/h2/V32__catalogo_guardado.sql"),
                StandardCharsets.UTF_8));
        clock = new JdbcSheetStoreTest.MovableClock();
        store = new JdbcCatalogStore(jdbc, clock);
    }

    @Test
    void savedCompressedWithItsTimeAndReplaced() {
        String html = "<a class='idc_master'>Vélez</a>".repeat(20_000);
        store.put("sidgad:ls", html);
        assertThat(store.get("sidgad:ls")).contains(new Stored(html, clock.now));
        assertThat(store.get("sidgad:portal")).isEmpty();
        byte[] gz = jdbc.queryForObject("select body_gz from source_catalog where catalog_key = 'sidgad:ls'", byte[].class);
        assertThat(gz.length).isLessThan(html.length() / 10);

        clock.now = clock.now.plus(Duration.ofDays(1));
        store.put("sidgad:ls", "v2");
        assertThat(store.get("sidgad:ls")).contains(new Stored("v2", clock.now));
        assertThat(jdbc.queryForObject("select count(*) from source_catalog", Integer.class)).isEqualTo(1);
    }
}
