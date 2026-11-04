package com.madsports.sidgad.tables;

import com.madsports.sidgad.SheetStore;
import com.madsports.sidgad.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lleva a las tablas propias ({@link SidgadNormalizer}) lo guardado de SIDGAD que cambió desde la última carga sin
 * errores ({@link #MARK}): las copias de {@code source_snapshot} con contenido nuevo y las actas bajadas de nuevo. La
 * primera vez (sin marca) recorre todo: es la carga inicial. Solo lee la base, nunca pide nada a SIDGAD. Corre después de
 * cada sincronización y al arrancar ({@code madsports.sidgad.tables-on-startup}). Lo guardado en el mismo instante en
 * que empieza una carga lo vuelve a tomar la siguiente (desde la marca, inclusive): así no se pierde nada, y repetirlo
 * deja las mismas filas.
 */
@Component
public class SidgadTablesLoader {

    private static final Logger log = LoggerFactory.getLogger(SidgadTablesLoader.class);

    /** Dónde se anota la última carga sin errores. */
    static final String MARK = "sidgad:tables:loaded";

    private static final Pattern FRAGMENT = Pattern.compile("sidgad:(calendar|standings|stats):(\\d+)");
    private static final Pattern PROFILE = Pattern.compile("sidgad:profile:(\\d+):(\\d+)");

    /** Lo que hizo una carga: competiciones del catálogo, datos de competición, actas y fichas normalizados. */
    public record Load(int competitions, int fragments, int sheets, int profiles, int errors, Duration took) {

        /** Para el mail de la sincronización. */
        public String summary() {
            return "Tablas propias: " + competitions + " competiciones del catálogo; " + fragments
                    + " tablas, calendarios y estadísticas; " + sheets + " actas; " + profiles + " fichas"
                    + (errors == 0 ? "." : "; " + errors + " con errores (se reintentan en la próxima carga).");
        }
    }

    private final SidgadNormalizer normalizer;
    private final SnapshotStore snapshots;
    private final SheetStore sheets;
    private final Clock clock;
    private final boolean onStartup;
    /** Una carga a la vez: la del arranque y la de después de la sincronización no se pisan. */
    private final ReentrantLock running = new ReentrantLock();

    public SidgadTablesLoader(SidgadNormalizer normalizer, SnapshotStore snapshots, SheetStore sheets, Clock clock,
                              @Value("${madsports.sidgad.tables-on-startup:true}") boolean onStartup) {
        this.normalizer = normalizer;
        this.snapshots = snapshots;
        this.sheets = sheets;
        this.clock = clock;
        this.onStartup = onStartup;
    }

    /** Al arrancar, en segundo plano: pone al día las tablas con lo guardado (la primera vez, la carga inicial). */
    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        if (onStartup) {
            Thread.ofVirtual().name("sidgad-tablas").start(this::catchUp);
        }
    }

    /** La carga: vacía si ya hay otra en curso (la próxima toma lo que quede). */
    public Optional<Load> catchUp() {
        if (!running.tryLock()) {
            log.info("Ya hay una carga de las tablas de SIDGAD en curso: se saltea esta");
            return Optional.empty();
        }
        try {
            Load load = load();
            log.info("Tablas de SIDGAD al día: {} competiciones, {} datos de competición, {} actas, {} fichas, "
                            + "{} errores, en {} s", load.competitions(), load.fragments(), load.sheets(),
                    load.profiles(), load.errors(), load.took().toSeconds());
            return Optional.of(load);
        } finally {
            running.unlock();
        }
    }

    private Load load() {
        Instant start = clock.instant();
        long t0 = System.nanoTime();
        Instant since = snapshots.get(MARK).map(s -> Instant.parse(s.body().trim())).orElse(Instant.EPOCH);
        List<String> keys = snapshots.changedSince(since);
        int competitions = 0, fragments = 0, sheetCount = 0, profiles = 0, errors = 0;
        // Primero el catálogo: las tablas, calendarios y estadísticas van con su competición.
        if (keys.contains("sidgad:ls") || keys.contains("sidgad:portal")) {
            try {
                Optional<String> catalog = body("sidgad:ls");
                if (catalog.isPresent()) {
                    competitions = normalizer.catalog(catalog.get(), body("sidgad:portal").orElse(null));
                }
            } catch (RuntimeException e) {
                errors++;
                log.warn("No se pudo pasar el catálogo de SIDGAD a las tablas: {}", e.getMessage());
            }
        }
        List<String> profileKeys = new ArrayList<>();
        for (String key : keys) {
            Matcher f = FRAGMENT.matcher(key);
            if (f.matches()) {
                try {
                    int idc = Integer.parseInt(f.group(2));
                    String html = body(key).orElse("");
                    switch (f.group(1)) {
                        case "calendar" -> normalizer.calendar(idc, html);
                        case "standings" -> normalizer.standings(idc, html);
                        default -> normalizer.playerStats(idc, html);
                    }
                    fragments++;
                } catch (RuntimeException e) {
                    errors++;
                    log.warn("No se pudo pasar {} a las tablas: {}", key, e.getMessage());
                }
            } else if (PROFILE.matcher(key).matches()) {
                profileKeys.add(key);
            }
        }
        for (int idp : sheets.changedSince(since)) {
            try {
                normalizer.sheet(idp, sheets.get(idp).orElse(""));
                sheetCount++;
            } catch (RuntimeException e) {
                errors++;
                log.warn("No se pudo pasar el acta {} a las tablas: {}", idp, e.getMessage());
            }
        }
        for (String key : profileKeys) {
            Matcher p = PROFILE.matcher(key);
            p.matches();
            try {
                normalizer.playerSeason(Integer.parseInt(p.group(1)), Integer.parseInt(p.group(2)),
                        body(key).orElse(""));
                profiles++;
            } catch (RuntimeException e) {
                errors++;
                log.warn("No se pudo pasar {} a las tablas: {}", key, e.getMessage());
            }
        }
        // Sin errores, la próxima carga toma lo cambiado desde ahora; con errores, vuelve a tomar desde la anterior.
        // La marca, truncada a microsegundos: la base redondea las fechas a esa precisión, y algo guardado en el mismo
        // instante en que empezó esta carga no puede quedar antes de la marca (se perdería).
        if (errors == 0) {
            snapshots.put(MARK, start.truncatedTo(ChronoUnit.MICROS).toString());
        }
        return new Load(competitions, fragments, sheetCount, profiles, errors, Duration.ofNanos(System.nanoTime() - t0));
    }

    private Optional<String> body(String key) {
        return snapshots.get(key).map(SnapshotStore.Stored::body);
    }
}
