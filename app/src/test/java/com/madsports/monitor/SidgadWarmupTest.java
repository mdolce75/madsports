package com.madsports.monitor;

import com.madsports.catalog.CatalogService;
import com.madsports.sidgad.SnapshotStore;
import com.madsports.sidgad.model.Federation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** La sincronización de madrugada, contra el SIDGAD simulado con páginas guardadas (las del monitor). */
class SidgadWarmupTest {

    static final Instant START = Instant.parse("2026-10-20T08:00:00Z");

    /** Un almacén de copias en memoria. */
    static class MemoryStore implements SnapshotStore {
        final Map<String, Stored> rows = new ConcurrentHashMap<>();

        @Override
        public Optional<Stored> get(String key) {
            return Optional.ofNullable(rows.get(key));
        }

        @Override
        public boolean put(String key, String body) {
            rows.put(key, new Stored(body, START));
            return true;
        }
    }

    /** El SIDGAD simulado que cuenta actas y fichas; las competiciones cuyo id está en {@code failing} fallan al renovar. */
    static class CountingSite extends SidgadMonitorTest.FakeSite {
        final AtomicInteger sheets = new AtomicInteger();
        final Set<String> profiles = ConcurrentHashMap.newKeySet();
        final Set<Instant> asked = ConcurrentHashMap.newKeySet();
        final Set<Integer> failing = ConcurrentHashMap.newKeySet();

        @Override
        public String playerStats(int idc) {
            return "";
        }

        @Override
        public String gameSheet(int idc, int idp) {
            sheets.incrementAndGet();
            return super.gameSheet(idc, idp);
        }

        @Override
        public String playerSeason(int playerId, int seasonId, Instant notBefore) {
            profiles.add(playerId + "/" + seasonId);
            asked.add(notBefore);
            return "";
        }

        @Override
        public boolean refresh(Fragment f, int idc) {
            if (failing.contains(idc)) {
                throw new SidgadException("SIDGAD respondió 500");
            }
            return super.refresh(f, idc);
        }
    }

    private static SidgadWarmup warmup(CountingSite site, SnapshotStore store) {
        return warmup(site, store, START);
    }

    private static SidgadWarmup warmup(CountingSite site, SnapshotStore store, Instant now) {
        return new SidgadWarmup(new CatalogService(site, SidgadMonitorTest.PROPS), site,
                Clock.fixed(now, ZoneOffset.UTC), List.of("FPP"), false, store, 2);
    }

    @Test
    void onAMatchDayOnlyTheCompetitionsPlayingTodayAndTheirPlayers() {
        // El calendario de prueba tiene partidos el 03/10 y el 10/10 (y el 13/10 sin jugar).
        CountingSite all = new CountingSite();
        warmup(all, new MemoryStore()).warm(Federation.FPP, 2026);
        CountingSite site = new CountingSite();
        MemoryStore store = new MemoryStore();

        SidgadWarmup.Result r = warmup(site, store, Instant.parse("2026-10-10T21:00:00Z")).matchDay(Federation.FPP, 2026);

        // Todas las competiciones de prueba usan ese calendario: todas juegan hoy.
        assertThat(r.competitions()).isEqualTo(24);
        assertThat(r.fragments()).isEqualTo(3 * 24);
        // Solo las fichas de quienes jugaron hoy, no las de todo el año; y no se anota como pasada.
        assertThat(r.profiles()).isPositive().isLessThanOrEqualTo(all.profiles.size());
        assertThat(store.rows).doesNotContainKey(SidgadWarmup.lastSyncKey(Federation.FPP));
    }

    @Test
    void onADayWithoutMatchesTheMatchDayPassDoesNothing() {
        CountingSite site = new CountingSite();

        SidgadWarmup.Result r = warmup(site, new MemoryStore(), Instant.parse("2026-10-11T21:00:00Z"))
                .matchDay(Federation.FPP, 2026);

        assertThat(r.competitions()).isZero();
        assertThat(site.refreshed).isEmpty();
        assertThat(site.profiles).isEmpty();
    }

    @Test
    void theFirstPassRenewsEveryCompetitionReadsTheSheetsAndEveryPlayersProfile() {
        CountingSite site = new CountingSite();
        MemoryStore store = new MemoryStore();

        SidgadWarmup.Result r = warmup(site, store).warm(Federation.FPP, 2026);

        assertThat(r.competitions()).isEqualTo(24);
        // Tabla, calendario y estadísticas de cada competición, renovados.
        assertThat(r.fragments()).isEqualTo(3 * 24);
        assertThat(site.refreshed).hasSize(3 * 24).contains("CALENDAR:779", "STANDINGS:779", "STATS:779");
        // Cada partido jugado de cada competición: su acta.
        assertThat(r.sheets()).isPositive().isEqualTo(site.sheets.get());
        // Sin pasada anterior: la ficha de cada jugador de las planillas, bajada de nuevo desde el comienzo.
        assertThat(r.profiles()).isPositive().isEqualTo(site.profiles.size());
        assertThat(site.asked).containsExactly(START);
        assertThat(r.errors()).isZero();
        // Sin errores, queda anotada.
        assertThat(store.rows.get(SidgadWarmup.lastSyncKey(Federation.FPP)).body()).isEqualTo(START.toString());
    }

    @Test
    void onlyThePlayersWhoPlayedSinceTheLastPassAreRefreshed() {
        CountingSite site = new CountingSite();
        MemoryStore store = new MemoryStore();
        // La pasada anterior fue después de todos los partidos del calendario.
        store.put(SidgadWarmup.lastSyncKey(Federation.FPP), "2026-12-01T08:00:00Z");

        SidgadWarmup.Result r = warmup(site, store).warm(Federation.FPP, 2026);

        assertThat(r.sheets()).isPositive();
        assertThat(r.profiles()).isZero();
        assertThat(site.profiles).isEmpty();
    }

    @Test
    void anErrorDoesNotStopThePassButItIsNotNotedAsDone() {
        CountingSite site = new CountingSite();
        site.failing.add(779);
        MemoryStore store = new MemoryStore();

        SidgadWarmup.Result r = warmup(site, store).warm(Federation.FPP, 2026);

        // Las 3 renovaciones de la 779 fallan; el resto sigue, y la 779 sigue con su copia (el calendario se lee igual).
        assertThat(r.errors()).isEqualTo(3);
        assertThat(r.fragments()).isEqualTo(3 * 23);
        assertThat(r.sheets()).isPositive();
        assertThat(r.profiles()).isPositive();
        // Con errores no avanza: la próxima pasada vuelve a cubrir a quienes jugaron.
        assertThat(store.rows).doesNotContainKey(SidgadWarmup.lastSyncKey(Federation.FPP));
    }
}
