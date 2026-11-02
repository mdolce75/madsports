package com.madsports.config;

import com.madsports.sidgad.CatalogStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * La copia del catálogo de la fuente guardada en la base (tabla {@code source_catalog}, comprimida como las actas de
 * {@link JdbcSheetStore}): una fila por parte ({@code sidgad:ls}, {@code sidgad:portal}).
 */
@Component
public class JdbcCatalogStore implements CatalogStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcCatalogStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<Stored> get(String key) {
        List<Stored> rows = jdbc.query("select body_gz, fetched_at from source_catalog where catalog_key = ?",
                (rs, i) -> new Stored(JdbcSheetStore.gunzip(rs.getBytes(1)), rs.getTimestamp(2).toInstant()), key);
        return rows.stream().findFirst();
    }

    @Override
    public void put(String key, String body) {
        byte[] gz = JdbcSheetStore.gzip(body);
        Timestamp now = Timestamp.from(clock.instant());
        int updated = jdbc.update("update source_catalog set body_gz = ?, fetched_at = ? where catalog_key = ?",
                gz, now, key);
        if (updated == 0) {
            try {
                jdbc.update("insert into source_catalog (catalog_key, body_gz, fetched_at) values (?, ?, ?)", key, gz, now);
            } catch (DuplicateKeyException e) {
                // Otro pedido la guardó recién: queda la suya.
            }
        }
    }
}
