package com.madsports.monitor;

import com.madsports.catalog.CatalogService;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.SnapshotStore;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.parse.SidgadParser;
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
 * Sincronización de madrugada con SIDGAD: deja en la base (copias de {@link SidgadClient}) lo que leen los informes, así
 * no dependen de SIDGAD cuando alguien pide uno. Por cada competición del año renueva la tabla, el calendario y las
 * estadísticas por jugador; baja las actas de los partidos jugados que faltan (y las de sus primeros días, que SIDGAD
 * puede corregir) y la ficha de temporada de los jugadores que jugaron desde la última pasada. Va despacio
 * ({@code madsports.sidgad.sync-concurrency} pedidos a la vez) y un error no la corta: queda la copia anterior.
 * {@code madsports.sidgad.warmup-cron} ("-" la apaga) y {@code madsports.sidgad.warmup-federations} (FPP); la AMP se
 * suma sola cuando está habilitada ({@code madsports.signup.amp}).
 */
@Component
public class SidgadWarmup {

    private static final Logger log = LoggerFactory.getLogger(SidgadWarmup.class);
    private static final ZoneId AR = ZoneId.of("America/Argentina/Buenos_Aires");

    /**
     * Lo que hizo una pasada.
     *
     * @param fragments tablas, calendarios y estadísticas renovados
     * @param changed   de esos, los que cambiaron
     * @param profiles  fichas de temporada bajadas (las de quienes jugaron desde la pasada anterior)
     */
    public record Result(int competitions, int fragments, int changed, int sheets, int profiles, int errors,
                         Duration took) {
    }

    private final CatalogService catalog;
    private final SidgadClient sidgad;
    private final Clock clock;
    private final List<Federation> federations;
    /** Dónde se anota la última pasada sin errores de cada federación (null en los tests sin base). */
    private final SnapshotStore store;
    private final int concurrency;

    public SidgadWarmup(CatalogService catalog, SidgadClient sidgad, Clock clock, List<String> federations, boolean amp) {
        this(catalog, sidgad, clock, federations, amp, null, 2);
    }

    @Autowired
    public SidgadWarmup(CatalogService catalog, SidgadClient sidgad, Clock clock,
                        @Value("${madsports.sidgad.warmup-federations:FPP}") List<String> federations,
                        @Value("${madsports.signup.amp:false}") boolean amp,
                        @Autowired(required = false) SnapshotStore store,
                        @Value("${madsports.sidgad.sync-concurrency:2}") int concurrency) {
        this.catalog = catalog;
        this.sidgad = sidgad;
        this.clock = clock;
        this.federations = Monitored.federations(federations.stream().map(Federation::parse).toList(), amp);
        this.store = store;
        this.concurrency = Math.max(1, concurrency);
    }

    /** Una pasada a la vez: la del arranque, la de la madrugada y las de días de partido no se pisan. */
    private final ReentrantLock running = new ReentrantLock();
    private volatile boolean syncOnStartup = true;

    /** {@code madsports.sidgad.sync-on-startup}: sincronizar al arrancar (cada deploy), además de lo programado. */
    @Autowired
    void setSyncOnStartup(@Value("${madsports.sidgad.sync-on-startup:true}") boolean on) {
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
                run();
            });
        }
    }

    @Scheduled(cron = "${madsports.sidgad.warmup-cron:0 0 5 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void run() {
        if (!running.tryLock()) {
            log.info("Ya hay una sincronización de SIDGAD en curso: se saltea esta");
            return;
        }
        try {
            refreshCatalog();
            for (Federation f : federations) {
                try {
                    Result r = warm(f, catalog.defaultYear());
                    log.info("Sincronización de SIDGAD ({}): {} competiciones; {} tablas, calendarios y estadísticas ({}"
                                    + " cambiaron); {} actas; {} fichas de jugador; {} errores; en {} s", f,
                            r.competitions(), r.fragments(), r.changed(), r.sheets(), r.profiles(), r.errors(),
                            r.took().toSeconds());
                } catch (RuntimeException e) {
                    log.warn("Falló la sincronización de SIDGAD ({}): {}", f, e.getMessage());
                }
            }
        } finally {
            running.unlock();
        }
    }

    /** La bajada del día del catálogo (competiciones, clubes, equipos): se guarda y se registra qué cambió. */
    void refreshCatalog() {
        SidgadClient.CatalogRefresh r = sidgad.refreshCatalog();
        if (!r.downloaded()) {
            log.warn("No se pudo actualizar el catálogo de SIDGAD: se sigue con la copia guardada");
        } else if (r.changes().isEmpty()) {
            log.info("Catálogo de SIDGAD actualizado, sin cambios en competiciones ni equipos");
        } else {
            log.info("Catálogo de SIDGAD actualizado, {} cambio(s):\n  {}", r.changes().size(),
                    String.join("\n  ", r.changes()));
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
     * Pasadas extra en días de partido ({@code madsports.sidgad.matchday-cron}; "-" las apaga): solo las competiciones
     * con partidos hoy según el calendario guardado, así un resultado de la tarde aparece sin esperar a la madrugada.
     */
    @Scheduled(cron = "${madsports.sidgad.matchday-cron:0 0 12,15,18,21 * * SAT,SUN}", zone = "America/Argentina/Buenos_Aires")
    public void runMatchDay() {
        if (!running.tryLock()) {
            log.info("Ya hay una sincronización de SIDGAD en curso: se saltea la de día de partido");
            return;
        }
        try {
            for (Federation f : federations) {
                try {
                    Result r = matchDay(f, catalog.defaultYear());
                    if (r.competitions() > 0) {
                        log.info("Pasada de día de partido de SIDGAD ({}): {} competiciones con partidos hoy; {} tablas,"
                                        + " calendarios y estadísticas ({} cambiaron); {} fichas de jugador; {} errores;"
                                        + " en {} s", f, r.competitions(), r.fragments(), r.changed(), r.profiles(),
                                r.errors(), r.took().toSeconds());
                    }
                } catch (RuntimeException e) {
                    log.warn("Falló la pasada de día de partido de SIDGAD ({}): {}", f, e.getMessage());
                }
            }
        } finally {
            running.unlock();
        }
    }

    /**
     * La pasada de día de partido: las competiciones con algún partido hoy (según su calendario guardado), con las fichas
     * de quienes jugaron hoy. No se anota como pasada: la de la madrugada sigue cubriendo desde la anterior.
     */
    public Result matchDay(Federation f, int year) {
        LocalDate today = clock.instant().atZone(AR).toLocalDate();
        List<Competition> playing = catalog.competitions(f, year).stream()
                .filter(c -> playsOn(c, today))
                .toList();
        return pass(f, playing, Optional.of(today), false);
    }

    /** ¿La competición tiene algún partido ese día? Se mira el calendario guardado; si no se puede leer, no. */
    private boolean playsOn(Competition c, LocalDate day) {
        try {
            return SidgadParser.calendar(sidgad.calendar(c.id())).stream().anyMatch(m -> day.equals(m.date()));
        } catch (RuntimeException e) {
            log.info("No se pudo leer el calendario de {} para la pasada de hoy: {}", c.id(), e.getMessage());
            return false;
        }
    }

    /** Renueva los datos de cada competición del año, baja las actas que faltan y las fichas de quienes jugaron. */
    public Result warm(Federation f, int year) {
        return pass(f, catalog.competitions(f, year), since(f), true);
    }

    /**
     * Una pasada sobre esas competiciones: renueva sus datos, baja las actas que faltan y las fichas de quienes jugaron
     * desde {@code since} (vacío: todos). Con {@code note}, si no hubo errores queda anotada como la última pasada.
     */
    private Result pass(Federation f, List<Competition> comps, Optional<LocalDate> since, boolean note) {
        Instant start = clock.instant();
        long t0 = System.nanoTime();
        Set<Long> players = ConcurrentHashMap.newKeySet();   // jugador y temporada: id * 100_000 + temporada
        AtomicInteger fragments = new AtomicInteger();
        AtomicInteger changed = new AtomicInteger();
        AtomicInteger sheets = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        Semaphore slots = new Semaphore(concurrency);
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> work = new ArrayList<>();
            for (Competition c : comps) {
                // Tabla, calendario y estadísticas, renovados: las copias guardadas valen para el día.
                for (SidgadClient.Fragment fr : SidgadClient.Fragment.values()) {
                    work.add(io.submit(() -> paced(slots, () -> {
                        if (sidgad.refresh(fr, c.id())) {
                            changed.incrementAndGet();
                        }
                        fragments.incrementAndGet();
                    })));
                }
            }
            errors.addAndGet(waitAll(work));
            work.clear();
            for (Competition c : comps) {
                List<Match> played;
                try {
                    played = SidgadParser.calendar(sidgad.calendar(c.id())).stream()
                            .filter(m -> m.played() && m.idp() > 0).toList();
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                    continue;
                }
                for (Match m : played) {
                    // Las actas guardadas no se vuelven a bajar (salvo en sus primeros días); de quienes jugaron desde
                    // la pasada anterior, la ficha.
                    boolean recent = since.isEmpty() || m.date() == null || !m.date().isBefore(since.get());
                    work.add(io.submit(() -> paced(slots, () -> {
                        for (LineupPlayer p : SidgadParser.lineups(sidgad.gameSheet(c.id(), m.idp()))) {
                            if (recent && p.playerId() > 0) {
                                players.add(key(p.playerId(), c.seasonId()));
                            }
                        }
                        sheets.incrementAndGet();
                    })));
                }
            }
            errors.addAndGet(waitAll(work));
            // Las fichas, bajadas de nuevo (cambian con cada partido): desde ahora valen para el día.
            List<Future<?>> profiles = new ArrayList<>();
            for (long k : players) {
                profiles.add(io.submit(() -> paced(slots,
                        () -> sidgad.playerSeason((int) (k / 100_000), (int) (k % 100_000), start))));
            }
            errors.addAndGet(waitAll(profiles));
        }
        // Sin errores, la próxima pasada busca quiénes jugaron desde hoy; con errores, vuelve a cubrir desde la anterior.
        if (note && errors.get() == 0 && store != null) {
            try {
                store.put(lastSyncKey(f), start.toString());
            } catch (RuntimeException e) {
                log.warn("No se pudo anotar la sincronización de {}: {}", f, e.getMessage());
            }
        }
        return new Result(comps.size(), fragments.get(), changed.get(), sheets.get(), players.size(), errors.get(),
                Duration.ofNanos(System.nanoTime() - t0));
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
