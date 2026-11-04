package com.madsports.config;

import com.madsports.sidgad.SnapshotStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Las copias de la fuente guardadas en la base (tabla {@code source_snapshot}, comprimidas como las actas de
 * {@link JdbcSheetStore}): una fila por clave ({@code sidgad:ls}, {@code sidgad:standings:779}, …).
 */
@Component
public class JdbcSnapshotStore implements SnapshotStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcSnapshotStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<Stored> get(String key) {
        List<Stored> rows = jdbc.query(
                "select body_gz, fetched_at, changed_at from source_snapshot where snapshot_key = ?",
                (rs, i) -> new Stored(JdbcSheetStore.gunzip(rs.getBytes(1)), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3).toInstant()), key);
        return rows.stream().findFirst();
    }

    @Override
    public boolean put(String key, String body) {
        byte[] gz = JdbcSheetStore.gzip(body);
        Timestamp now = Timestamp.from(clock.instant());
        List<byte[]> old = jdbc.query("select body_gz from source_snapshot where snapshot_key = ?",
                (rs, i) -> rs.getBytes(1), key);
        if (old.isEmpty()) {
            try {
                jdbc.update("insert into source_snapshot (snapshot_key, body_gz, fetched_at, changed_at) values (?, ?, ?, ?)",
                        key, gz, now, now);
                return true;
            } catch (DuplicateKeyException e) {
                // Otro pedido la guardó recién: queda la suya.
                return false;
            }
        }
        // Se compara el contenido (no el gzip): sin cambios, solo se actualiza la hora de la bajada.
        if (JdbcSheetStore.gunzip(old.getFirst()).equals(body)) {
            jdbc.update("update source_snapshot set fetched_at = ? where snapshot_key = ?", now, key);
            return false;
        }
        jdbc.update("update source_snapshot set body_gz = ?, fetched_at = ?, changed_at = ? where snapshot_key = ?",
                gz, now, now, key);
        return true;
    }

    @Override
    public List<String> changedSince(Instant since) {
        return jdbc.queryForList("select snapshot_key from source_snapshot where changed_at >= ? order by snapshot_key",
                String.class, Timestamp.from(since));
    }

    /** Copias guardadas (para el registro de la sincronización). */
    public int count() {
        Integer n = jdbc.queryForObject("select count(*) from source_snapshot", Integer.class);
        return n == null ? 0 : n;
    }
}
