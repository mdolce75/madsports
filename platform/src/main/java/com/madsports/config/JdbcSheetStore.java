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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Las actas de SIDGAD guardadas en la base (tabla {@code sidgad_game_sheet}, comprimidas: unos 20 KB cada una). En sus
 * primeros {@link #SETTLING} días SIDGAD puede corregir un acta (un gol mal cargado, una tarjeta): en ese lapso se
 * vuelve a bajar si la guardada tiene más de {@link #RECHECK}; después queda fija.
 */
@Component
public class JdbcSheetStore implements SheetStore {

    static final Duration SETTLING = Duration.ofDays(7);
    static final Duration RECHECK = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcSheetStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private record Row(byte[] html, Instant first, Instant fetched) {
    }

    @Override
    public Optional<String> get(int idp) {
        List<Row> rows = jdbc.query("select html_gz, first_fetched_at, fetched_at from sidgad_game_sheet where idp = ?",
                (rs, i) -> new Row(rs.getBytes(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()), idp);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row r = rows.getFirst();
        Instant now = clock.instant();
        boolean settled = !r.first().plus(SETTLING).isAfter(now);
        boolean recent = r.fetched().plus(RECHECK).isAfter(now);
        return settled || recent ? Optional.of(gunzip(r.html())) : Optional.empty();
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
