package com.madsports.monitor;

import com.madsports.catalog.CatalogService;
import com.madsports.sidgad.model.Federation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** La precarga de madrugada, contra el SIDGAD simulado con páginas guardadas (las del monitor). */
class SidgadWarmupTest {

    @Test
    void readsThePlayedSheetsAndRefreshesEachPlayersProfile() {
        Instant start = Instant.parse("2026-10-20T08:00:00Z");
        AtomicInteger sheets = new AtomicInteger();
        Set<String> profiles = ConcurrentHashMap.newKeySet();
        Set<Instant> asked = ConcurrentHashMap.newKeySet();
        SidgadMonitorTest.FakeSite site = new SidgadMonitorTest.FakeSite() {
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
        };
        SidgadWarmup warmup = new SidgadWarmup(new CatalogService(site, SidgadMonitorTest.PROPS), site,
                Clock.fixed(start, ZoneOffset.UTC), List.of("FPP"), false);

        SidgadWarmup.Result r = warmup.warm(Federation.FPP, 2026);

        assertThat(r.competitions()).isEqualTo(24);
        // Cada partido jugado de cada competición: su acta.
        assertThat(r.sheets()).isPositive().isEqualTo(sheets.get());
        // La ficha de cada jugador de las planillas, bajada de nuevo desde el comienzo de la precarga.
        assertThat(r.profiles()).isPositive().isEqualTo(profiles.size());
        assertThat(asked).containsExactly(start);
        assertThat(r.errors()).isZero();
    }
}
