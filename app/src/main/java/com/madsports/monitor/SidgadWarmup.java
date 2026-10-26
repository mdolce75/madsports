package com.madsports.monitor;

import com.madsports.catalog.CatalogService;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.parse.SidgadParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Precarga de madrugada: lee de SIDGAD las actas de los partidos jugados en el año (las nuevas: las ya guardadas no se
 * vuelven a bajar) y la ficha de temporada de cada jugador, así el primer informe del día ya encuentra todo leído (el
 * del club completo pasa de ~70 s a unos pocos). Usa el cupo normal de pedidos a SIDGAD (6 a la vez), sin apuro.
 * {@code madsports.sidgad.warmup-cron} ("-" la apaga) y {@code madsports.sidgad.warmup-federations} (FPP).
 */
@Component
public class SidgadWarmup {

    private static final Logger log = LoggerFactory.getLogger(SidgadWarmup.class);

    /** Lo que leyó una precarga. */
    public record Result(int competitions, int sheets, int profiles, int errors, Duration took) {
    }

    private final CatalogService catalog;
    private final SidgadClient sidgad;
    private final Clock clock;
    private final List<Federation> federations;

    public SidgadWarmup(CatalogService catalog, SidgadClient sidgad, Clock clock,
                        @Value("${madsports.sidgad.warmup-federations:FPP}") List<String> federations) {
        this.catalog = catalog;
        this.sidgad = sidgad;
        this.clock = clock;
        this.federations = federations.stream().map(Federation::parse).toList();
    }

    @Scheduled(cron = "${madsports.sidgad.warmup-cron:0 0 5 * * *}", zone = "America/Argentina/Buenos_Aires")
    public void run() {
        for (Federation f : federations) {
            try {
                Result r = warm(f, catalog.defaultYear());
                log.info("Precarga de SIDGAD ({}): {} competiciones, {} actas, {} fichas de jugador, {} errores, en {} s",
                        f, r.competitions(), r.sheets(), r.profiles(), r.errors(), r.took().toSeconds());
            } catch (RuntimeException e) {
                log.warn("Falló la precarga de SIDGAD ({}): {}", f, e.getMessage());
            }
        }
    }

    /** Lee las actas de los partidos jugados de cada competición del año y la ficha de temporada de sus jugadores. */
    public Result warm(Federation f, int year) {
        Instant start = clock.instant();
        long t0 = System.nanoTime();
        List<Competition> comps = catalog.competitions(f, year);
        Set<Long> players = ConcurrentHashMap.newKeySet();   // jugador y temporada: id * 100_000 + temporada
        AtomicInteger sheets = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> work = new ArrayList<>();
            for (Competition c : comps) {
                work.add(io.submit(() -> {
                    for (PlayerStats p : SidgadParser.playerStats(sidgad.playerStats(c.id()))) {
                        players.add(key(p.playerId(), c.seasonId()));
                    }
                    return null;
                }));
                List<Match> played;
                try {
                    played = SidgadParser.calendar(sidgad.calendar(c.id())).stream()
                            .filter(m -> m.played() && m.idp() > 0).toList();
                } catch (SidgadClient.SidgadException e) {
                    errors.incrementAndGet();
                    continue;
                }
                for (Match m : played) {
                    work.add(io.submit(() -> {
                        for (LineupPlayer p : SidgadParser.lineups(sidgad.gameSheet(c.id(), m.idp()))) {
                            if (p.playerId() > 0) {
                                players.add(key(p.playerId(), c.seasonId()));
                            }
                        }
                        sheets.incrementAndGet();
                        return null;
                    }));
                }
            }
            errors.addAndGet(waitAll(work));
            // Las fichas, bajadas de nuevo (cambian con cada partido): desde ahora valen para el día.
            List<Future<?>> profiles = new ArrayList<>();
            for (long k : players) {
                profiles.add(io.submit(() -> sidgad.playerSeason((int) (k / 100_000), (int) (k % 100_000), start)));
            }
            errors.addAndGet(waitAll(profiles));
            return new Result(comps.size(), sheets.get(), players.size(), errors.get(),
                    Duration.ofNanos(System.nanoTime() - t0));
        }
    }

    private static long key(int playerId, int seasonId) {
        return (long) playerId * 100_000 + seasonId;
    }

    /** Espera todo; un pedido que falla no corta la precarga (cuenta como error). */
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
