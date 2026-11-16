package com.madsports.monitor;

import com.madsports.account.MailContent;
import com.madsports.account.Mailer;
import com.madsports.account.Product;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.EnabledFederations;
import com.madsports.config.SiteUrls;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.SnapshotStore;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.sidgad.parse.SidgadParser;
import com.madsports.sidgad.tables.SidgadTablesLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sincronización de madrugada (3:00) con SIDGAD: deja en la base (copias de {@link SidgadClient}) lo que leen los informes, así
 * no dependen de SIDGAD cuando alguien pide uno. La primera vez (sin pasada anterior) baja todo el año. Después es
 * incremental y le pide a SIDGAD solo lo que falta: el calendario de cada competición en curso (las terminadas hace más
 * de {@link #DORMANT} días no se tocan) dice qué se jugó; de los partidos jugados desde la última pasada, el acta que
 * todavía no está guardada (un partido jugado no cambia, así que una guardada no se vuelve a pedir). La tabla y las
 * estadísticas, solo de las competiciones donde apareció un acta nueva o cambió el calendario; la ficha de temporada,
 * solo de quienes jugaron un partido cuya acta se bajó recién. Así un error no hace volver a bajar lo ya guardado, y la
 * carga de cada noche es mínima. Va despacio
 * ({@code madsports.sidgad.sync-concurrency} pedidos a la vez) y un error no la corta: queda la copia anterior.
 * {@code madsports.sidgad.warmup-cron} ("-" la apaga); las federaciones habilitadas ({@link EnabledFederations}). La
 * del arranque (cada deploy), solo con {@code madsports.sidgad.sync-on-startup}. Al terminar la del arranque y
 * la de madrugada, avisa por mail cómo quedó cada federación ({@code madsports.sidgad.sync-notify}).
 */
@Component
public class SidgadWarmup {

    private static final Logger log = LoggerFactory.getLogger(SidgadWarmup.class);
    private static final ZoneId AR = ZoneId.of("America/Argentina/Buenos_Aires");

    /** Una competición cuyo último partido es de hace más de esto ya no se renueva. */
    static final int DORMANT = 14;

    /**
     * Lo que hizo una pasada.
     *
     * @param competitions competiciones revisadas (en la incremental, las en curso)
     * @param fragments tablas, calendarios y estadísticas renovados
     * @param changed   de esos, los que cambiaron
     * @param sheets    actas pedidas a SIDGAD
     * @param kept      actas de partidos jugados que ya estaban guardadas y no se volvieron a pedir
     * @param profiles  fichas de temporada bajadas (las de quienes jugaron un partido con acta nueva o cambiada)
     */
    public record Result(int competitions, int fragments, int changed, int sheets, int kept, int profiles,
                         int errors, Duration took) {
    }

    private final CatalogService catalog;
    private final SidgadClient sidgad;
    private final Clock clock;
    private final List<Federation> federations;
    /** Dónde se anota la última pasada sin errores de cada federación (null en los tests sin base). */
    private final SnapshotStore store;
    private final int concurrency;

    /** Las federaciones habilitadas: la FPP, y la AMP si está encendida. */
    @Autowired
    public SidgadWarmup(CatalogService catalog, SidgadClient sidgad, Clock clock, EnabledFederations enabled,
                        @Autowired(required = false) SnapshotStore store,
                        @Value("${madsports.sidgad.sync-concurrency:2}") int concurrency) {
        this(catalog, sidgad, clock, enabled.list(), store, concurrency);
    }

    /** Con otras federaciones (los tests, solo la FPP). */
    SidgadWarmup(CatalogService catalog, SidgadClient sidgad, Clock clock, List<Federation> federations,
                 SnapshotStore store, int concurrency) {
        this.catalog = catalog;
        this.sidgad = sidgad;
        this.clock = clock;
        this.federations = List.copyOf(federations);
        this.store = store;
        this.concurrency = Math.max(1, concurrency);
    }

    /** Una pasada a la vez: la del arranque y la de la madrugada no se pisan. */
    private final ReentrantLock running = new ReentrantLock();
    private volatile boolean syncOnStartup;

    private SidgadTablesLoader tables;

    /** Al terminar cada sincronización, lo nuevo pasa a las tablas propias (sin él, los tests sin base, no). */
    @Autowired(required = false)
    void setTables(SidgadTablesLoader tables) {
        this.tables = tables;
    }

    private Mailer mailer;
    private SiteUrls urls;
    private String notifyTo = "";

    /**
     * Aviso por mail al terminar: a quién ({@code madsports.sidgad.sync-notify}; vacío = a nadie). Sin el mailer (los
     * tests sin la app) no se avisa.
     */
    @Autowired(required = false)
    void setNotify(Mailer mailer, SiteUrls urls,
                   @Value("${madsports.sidgad.sync-notify:info@madsports.com.ar}") String to) {
        this.mailer = mailer;
        this.urls = urls;
        this.notifyTo = to == null ? "" : to.trim();
    }

    /**
     * {@code madsports.sidgad.sync-on-startup} ({@code SIDGAD_SYNC_ON_STARTUP}): sincronizar al arrancar (cada deploy),
     * además de lo programado. Apagado por defecto: los deploys no le piden nada a SIDGAD.
     */
    @Autowired
    void setSyncOnStartup(@Value("${madsports.sidgad.sync-on-startup:false}") boolean on) {
        this.syncOnStartup = on;
    }

    /**
     * Al arrancar (cada deploy), una sincronización completa en segundo plano: el esquema ya lo migró Flyway antes de
     * que la app atienda, y así los datos de SIDGAD no esperan a la madrugada. No demora el arranque.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (syncOnStartup) {
            Thread.ofVirtual().name("sidgad-sync-arranque").start(() -> {
                log.info("Sincronización de SIDGAD al arrancar");
                run(true);
            });
        }
    }

    @Scheduled(cron = "${madsports.sidgad.warmup-cron:0 0 3 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void run() {
        run(false);
    }

    /** La pasada completa; {@code startup}: la del arranque (cada deploy) o la de madrugada. Las dos avisan al terminar. */
    void run(boolean startup) {
        // La sincronización es la única que va a SIDGAD: los informes leen lo que deja guardado.
        sidgad.syncing(() -> sync(startup));
    }

    private void sync(boolean startup) {
        if (!running.tryLock()) {
            log.info("Ya hay una sincronización de SIDGAD en curso: se saltea esta");
            return;
        }
        Instant start = clock.instant();
        try {
            String catalogLine = refreshCatalog();
            List<String> lines = new ArrayList<>();
            boolean ok = true;
            for (Federation f : federations) {
                try {
                    Result r = warm(f, catalog.defaultYear());
                    log.info("Sincronización de SIDGAD ({}): {} competiciones; {} tablas, calendarios y estadísticas ({}"
                                    + " cambiaron); {} actas pedidas ({} ya guardadas no se pidieron); {} fichas de"
                                    + " jugador; {} errores; en {} s", f, r.competitions(), r.fragments(), r.changed(),
                            r.sheets(), r.kept(), r.profiles(), r.errors(), r.took().toSeconds());
                    ok &= r.errors() == 0;
                    lines.add(status(f, r));
                } catch (RuntimeException e) {
                    log.warn("Falló la sincronización de SIDGAD ({}): {}", f, e.getMessage());
                    ok = false;
                    lines.add(f + ": falló (" + e.getMessage() + "). Los informes siguen con la copia anterior.");
                }
            }
            if (tables != null) {
                tables.catchUp().ifPresent(load -> lines.add(load.summary()));
            }
            notify(startup, ok, start, catalogLine, lines);
        } finally {
            running.unlock();
        }
    }

    /** Cómo quedó una federación, para el mail. */
    static String status(Federation f, Result r) {
        String what = r.competitions() + " competiciones; " + r.fragments() + " tablas, calendarios y estadísticas ("
                + r.changed() + " cambiaron); " + r.sheets() + " actas pedidas (" + r.kept()
                + " ya guardadas no se pidieron); " + r.profiles() + " fichas de jugador; en "
                + minutes(r.took()) + ".";
        return r.errors() == 0
                ? f + ": OK. " + what
                : f + ": con " + r.errors() + " errores (la próxima pasada los reintenta; mientras, sigue la copia"
                        + " anterior). " + what;
    }

    private static String minutes(Duration d) {
        long s = d.toSeconds();
        return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /** Avisa por mail que terminó la sincronización, cuándo empezó y terminó, y cómo quedó cada federación. */
    private void notify(boolean startup, boolean ok, Instant start, String catalogLine, List<String> lines) {
        if (mailer == null || notifyTo.isEmpty()) {
            return;
        }
        Instant end = clock.instant();
        String which = startup ? "del arranque (deploy)" : "de madrugada";
        String subject = "Sincronización de SIDGAD " + which + (ok ? ": terminó bien" : ": terminó con errores");
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add("Terminó la sincronización de SIDGAD " + which + ". " + (ok ? "Todo quedó al día en la base."
                : "Hubo errores: lo que no se pudo bajar sigue con la copia anterior."));
        paragraphs.add("Inicio: " + WHEN.format(start.atZone(AR)) + " · Fin: " + WHEN.format(end.atZone(AR))
                + " · Tardó " + minutes(Duration.between(start, end)) + " (hora argentina).");
        paragraphs.add(catalogLine);
        paragraphs.addAll(lines);
        try {
            mailer.send(new MailContent(notifyTo, "RinkReports: " + subject, paragraphs.getFirst(), "Sincronización",
                    subject, "equipo", paragraphs, "Abrir RinkReports", urls == null ? "" : urls.url(Product.REPORTS),
                    "Aviso automático de la sincronización con SIDGAD.", Product.REPORTS));
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar por mail el fin de la sincronización: {}", e.getMessage());
        }
    }

    /** La bajada del día del catálogo (competiciones, clubes, equipos): se guarda y se registra qué cambió. */
    String refreshCatalog() {
        SidgadClient.CatalogRefresh r = sidgad.refreshCatalog();
        if (!r.downloaded()) {
            log.warn("No se pudo actualizar el catálogo de SIDGAD: se sigue con la copia guardada");
            return "Catálogo: no se pudo actualizar, sigue la copia guardada.";
        } else if (r.changes().isEmpty()) {
            log.info("Catálogo de SIDGAD actualizado, sin cambios en competiciones ni equipos");
            return "Catálogo: actualizado, sin cambios en competiciones ni equipos.";
        } else {
            log.info("Catálogo de SIDGAD actualizado, {} cambio(s):\n  {}", r.changes().size(),
                    String.join("\n  ", r.changes()));
            return "Catálogo: actualizado, " + r.changes().size() + " cambio(s): " + String.join("; ", r.changes())
                    + ".";
        }
    }

    /** Clave donde se anota la última pasada sin errores de la federación. */
    static String lastSyncKey(Federation f) {
        return "sidgad:sync:" + f.name();
    }

    /** Desde qué día buscar quiénes jugaron: el de la última pasada sin errores; vacío si no hubo (todos). */
    Optional<LocalDate> since(Federation f) {
        if (store == null) {
            return Optional.empty();
        }
        try {
            return store.get(lastSyncKey(f)).map(s -> Instant.parse(s.body().trim()).atZone(AR).toLocalDate());
        } catch (RuntimeException e) {
            log.warn("No se pudo leer la última sincronización de {}: {}", f, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * La pasada de la madrugada (y la del arranque): sin pasada anterior, todo el año; si no, lo jugado desde el día de la
     * última pasada sin errores.
     */
    public Result warm(Federation f, int year) {
        return pass(f, catalog.competitions(f, year), since(f));
    }

    /** Los contadores que van sumando las tareas de una pasada, en paralelo. */
    private static final class Counters {
        final AtomicInteger fragments = new AtomicInteger();
        final AtomicInteger changed = new AtomicInteger();
        final AtomicInteger sheets = new AtomicInteger();
        final AtomicInteger kept = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
    }

    /**
     * Una pasada sobre esas competiciones. Sin {@code since}, completa: renueva tabla, calendario y estadísticas de cada
     * una, baja las actas que falten y la ficha de todos los jugadores. Con {@code since}, incremental: solo las
     * competiciones en curso, y de ellas los partidos jugados desde ese día cuya acta todavía no esté guardada (una
     * guardada no se vuelve a pedir) y la ficha de quienes jugaron esos partidos. Si no hubo errores queda anotada como
     * la última pasada.
     */
    private Result pass(Federation f, List<Competition> comps, Optional<LocalDate> since) {
        Instant start = clock.instant();
        long t0 = System.nanoTime();
        boolean full = since.isEmpty();
        LocalDate today = start.atZone(AR).toLocalDate();
        List<Competition> active = full ? comps : comps.stream().filter(c -> !dormant(c, today)).toList();
        Set<Long> players = ConcurrentHashMap.newKeySet();   // jugador y temporada: id * 100_000 + temporada
        Set<Integer> dataChanged = ConcurrentHashMap.newKeySet();   // competiciones con acta nueva o calendario cambiado
        Counters k = new Counters();
        Semaphore slots = new Semaphore(concurrency);
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            // 1) Los calendarios dicen qué se jugó; de paso, los escudos que falten.
            List<Future<?>> calendars = new ArrayList<>();
            for (Competition c : active) {
                calendars.add(io.submit(() -> paced(slots, () -> {
                    if (renew(SidgadClient.Fragment.CALENDAR, c, k)) {
                        dataChanged.add(c.id());
                    }
                })));
            }
            active.stream().flatMap(c -> c.teams().stream()).map(TeamRef::logo)
                    .filter(l -> l != null && !l.isBlank()).distinct()
                    .forEach(logo -> calendars.add(io.submit(() -> paced(slots, () -> sidgad.clubLogo(logo)))));
            k.errors.addAndGet(waitAll(calendars));

            // 2) Las actas que faltan de lo jugado desde la última pasada (una guardada no se vuelve a pedir).
            List<Future<?>> sheets = new ArrayList<>();
            for (Competition c : active) {
                List<Match> played;
                try {
                    played = SidgadParser.calendar(sidgad.calendar(c.id())).stream()
                            .filter(m -> m.played() && m.idp() > 0).toList();
                } catch (RuntimeException e) {
                    k.errors.incrementAndGet();
                    continue;
                }
                for (Match m : played) {
                    if (full || m.date() == null || !m.date().isBefore(since.get())) {
                        sheets.add(io.submit(() -> paced(slots, () -> sheet(c, m, players, dataChanged, k))));
                    }
                }
            }
            k.errors.addAndGet(waitAll(sheets));

            // 3) Tabla y estadísticas, solo de las competiciones con acta nueva o calendario cambiado (completa: todas).
            List<Future<?>> stats = new ArrayList<>();
            for (Competition c : active) {
                if (full || dataChanged.contains(c.id())) {
                    for (SidgadClient.Fragment fr : List.of(SidgadClient.Fragment.STANDINGS, SidgadClient.Fragment.STATS)) {
                        stats.add(io.submit(() -> paced(slots, () -> renew(fr, c, k))));
                    }
                }
            }
            k.errors.addAndGet(waitAll(stats));

            // 4) Las fichas de quienes jugaron un partido cuya acta se bajó recién.
            List<Future<?>> profiles = new ArrayList<>();
            for (long key : players) {
                profiles.add(io.submit(() -> paced(slots,
                        () -> sidgad.playerSeason((int) (key / 100_000), (int) (key % 100_000), start))));
            }
            k.errors.addAndGet(waitAll(profiles));
        }
        // Sin errores, la próxima pasada suma lo jugado desde hoy; con errores, vuelve a cubrir desde la anterior.
        if (k.errors.get() == 0 && store != null) {
            try {
                store.put(lastSyncKey(f), start.toString());
            } catch (RuntimeException e) {
                log.warn("No se pudo anotar la sincronización de {}: {}", f, e.getMessage());
            }
        }
        return new Result(active.size(), k.fragments.get(), k.changed.get(), k.sheets.get(), k.kept.get(),
                players.size(), k.errors.get(), Duration.ofNanos(System.nanoTime() - t0));
    }

    /**
     * El acta de un partido jugado. Si ya está guardada, no se la vuelve a pedir (un partido jugado no cambia): cuenta
     * como {@code kept}. Si falta, se baja de SIDGAD; cuando aparece, marca la competición como cambiada y suma a sus
     * jugadores para bajarles la ficha.
     */
    private void sheet(Competition c, Match m, Set<Long> players, Set<Integer> dataChanged, Counters k) {
        if (sidgad.storedSheet(m.idp()).isPresent()) {
            k.kept.incrementAndGet();
            return;
        }
        String body = sidgad.gameSheet(c.id(), m.idp());
        k.sheets.incrementAndGet();
        if (body.equals(SidgadClient.NOT_PUBLISHED)) {
            return;   // SIDGAD todavía no la publicó: la próxima pasada la reintenta.
        }
        dataChanged.add(c.id());
        for (LineupPlayer p : SidgadParser.lineups(body)) {
            if (p.playerId() > 0) {
                players.add(key(p.playerId(), c.seasonId()));
            }
        }
    }

    /** Renueva un dato de la competición y lo cuenta; true si cambió. */
    private boolean renew(SidgadClient.Fragment fr, Competition c, Counters k) {
        boolean diff = sidgad.refresh(fr, c.id());
        k.fragments.incrementAndGet();
        if (diff) {
            k.changed.incrementAndGet();
        }
        return diff;
    }

    /**
     * ¿Terminó hace más de {@link #DORMANT} días? Según el calendario guardado: todos sus partidos con fecha son de
     * antes de eso (uno suspendido o sin fecha que nunca se jugó no la mantiene en curso). Sin calendario guardado, no.
     */
    private boolean dormant(Competition c, LocalDate today) {
        try {
            Optional<String> cal = sidgad.storedFragment(SidgadClient.Fragment.CALENDAR, c.id());
            if (cal.isEmpty() || cal.get().isBlank()) {
                return false;
            }
            List<LocalDate> dates = SidgadParser.calendar(cal.get()).stream().map(Match::date)
                    .filter(java.util.Objects::nonNull).toList();
            LocalDate limit = today.minusDays(DORMANT);
            return !dates.isEmpty() && dates.stream().allMatch(d -> d.isBefore(limit));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Corre el pedido con uno de los lugares de la sincronización (va despacio: no compite con los informes). */
    private static Void paced(Semaphore slots, Runnable request) throws InterruptedException {
        slots.acquire();
        try {
            request.run();
            return null;
        } finally {
            slots.release();
        }
    }

    private static long key(int playerId, int seasonId) {
        return (long) playerId * 100_000 + seasonId;
    }

    /** Espera todo; un pedido que falla no corta la sincronización (cuenta como error). */
    private static int waitAll(List<Future<?>> work) {
        int errors = 0;
        for (Future<?> f : work) {
            try {
                f.get();
            } catch (ExecutionException e) {
                errors++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return errors + 1;
            }
        }
        return errors;
    }
}
