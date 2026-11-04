package com.madsports.config;

import com.madsports.sidgad.SheetStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Las actas de SIDGAD guardadas en la base (tabla {@code sidgad_game_sheet}, comprimidas: unos 20 KB cada una). Una vez
 * guardada se lee siempre de acá; la sincronización vuelve a bajar las de los partidos recientes (SIDGAD puede
 * corregirlas: un gol mal cargado, una tarjeta).
 */
@Component
public class JdbcSheetStore implements SheetStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcSheetStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<String> get(int idp) {
        List<byte[]> rows = jdbc.query("select html_gz from sidgad_game_sheet where idp = ?",
                (rs, i) -> rs.getBytes(1), idp);
        return rows.stream().findFirst().map(JdbcSheetStore::gunzip);
    }

    @Override
    public void put(int idp, String html) {
        byte[] gz = gzip(html);
        Timestamp now = Timestamp.from(clock.instant());
        int updated = jdbc.update("update sidgad_game_sheet set html_gz = ?, fetched_at = ? where idp = ?", gz, now, idp);
        if (updated == 0) {
            try {
                jdbc.update("insert into sidgad_game_sheet (idp, html_gz, first_fetched_at, fetched_at) values (?, ?, ?, ?)",
                        idp, gz, now, now);
            } catch (DuplicateKeyException e) {
                // Otro informe la guardó recién: queda la suya.
            }
        }
    }

    @Override
    public List<Integer> changedSince(Instant since) {
        return jdbc.queryForList("select idp from sidgad_game_sheet where fetched_at >= ? order by idp", Integer.class,
                Timestamp.from(since));
    }

    /** Actas guardadas (para el registro de la precarga). */
    public int count() {
        Integer n = jdbc.queryForObject("select count(*) from sidgad_game_sheet", Integer.class);
        return n == null ? 0 : n;
    }

    static byte[] gzip(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(out)) {
            z.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String gunzip(byte[] gz) {
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(z.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
