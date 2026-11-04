package com.madsports.monitor;

import com.madsports.account.MailContent;
import com.madsports.account.Mailer;
import com.madsports.catalog.CatalogService;
import com.madsports.sidgad.SnapshotStore;
import com.madsports.sidgad.model.Federation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

        /** Actas bajadas de nuevo (la pasada incremental). */
        final AtomicInteger resheets = new AtomicInteger();

        @Override
        public String refreshSheet(int idc, int idp) {
            resheets.incrementAndGet();
            return super.refreshSheet(idc, idp);
        }

        /** El calendario guardado de cada competición (para ver si terminó); null = ninguno. */
        String storedCalendar;

        @Override
        public Optional<String> storedFragment(Fragment f, int idc) {
            return f == Fragment.CALENDAR ? Optional.ofNullable(storedCalendar) : Optional.empty();
        }

        /** Escudos pedidos (sin bajarlos). */
        final Set<String> logos = ConcurrentHashMap.newKeySet();

        @Override
        public byte[] clubLogo(String file) {
            logos.add(file);
            return null;
        }
    }

    private static SidgadWarmup warmup(CountingSite site, SnapshotStore store) {
        return warmup(site, store, START);
    }

    private static SidgadWarmup warmup(CountingSite site, SnapshotStore store, Instant now) {
        return new SidgadWarmup(new CatalogService(site, SidgadMonitorTest.PROPS), site,
                Clock.fixed(now, ZoneOffset.UTC), List.of(Federation.FPP), store, 2);
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
    void onStartupItSyncsInTheBackgroundUnlessTurnedOff() throws Exception {
        CountingSite site = new CountingSite();
        MemoryStore store = new MemoryStore();
        SidgadWarmup w = warmup(site, store);

        w.onStartup();
        // En segundo plano: se espera a que anote la pasada.
        for (int i = 0; i < 200 && !store.rows.containsKey(SidgadWarmup.lastSyncKey(Federation.FPP)); i++) {
            Thread.sleep(50);
        }
        assertThat(store.rows).containsKey(SidgadWarmup.lastSyncKey(Federation.FPP));
        assertThat(site.refreshed).hasSize(3 * 24);

        CountingSite off = new CountingSite();
        SidgadWarmup quiet = warmup(off, new MemoryStore());
        quiet.setSyncOnStartup(false);
        quiet.onStartup();
        Thread.sleep(300);
        assertThat(off.refreshed).isEmpty();
    }

    @Test
    void whenTheStartupSyncEndsItMailsHowEachFederationIsLeft() {
        Mailer mailer = mock(Mailer.class);
        CountingSite site = new CountingSite();
        SidgadWarmup w = warmup(site, new MemoryStore());
        w.setNotify(mailer, null, "info@madsports.com.ar");

        w.run(true);

        ArgumentCaptor<MailContent> mail = ArgumentCaptor.forClass(MailContent.class);
        verify(mailer).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("info@madsports.com.ar");
        assertThat(mail.getValue().subject()).isEqualTo("RinkReports: Sincronización de SIDGAD del arranque (deploy): terminó bien");
        assertThat(mail.getValue().paragraphs()).anySatisfy(p -> assertThat(p).startsWith("FPP: OK. 24 competiciones;"))
                // Inicio, fin y cuánto tardó, en hora argentina (el reloj de la prueba está fijo).
                .contains("Inicio: 20/10/2026 05:00:00 · Fin: 20/10/2026 05:00:00 · Tardó 0 s (hora argentina).");
    }

    @Test
    void theNightlySyncMailsEveryNightWellOrWithErrors() {
        Mailer mailer = mock(Mailer.class);
        CountingSite site = new CountingSite();
        SidgadWarmup w = warmup(site, new MemoryStore());
        w.setNotify(mailer, null, "info@madsports.com.ar");

        w.run();
        ArgumentCaptor<MailContent> ok = ArgumentCaptor.forClass(MailContent.class);
        verify(mailer).send(ok.capture());
        assertThat(ok.getValue().subject()).endsWith("de madrugada: terminó bien");
        assertThat(ok.getValue().paragraphs()).anySatisfy(p -> assertThat(p).startsWith("FPP: OK."));

        // La segunda es incremental: sin partidos nuevos, solo los calendarios; el de la 779 falla.
        site.failing.add(779);
        w.run();
        ArgumentCaptor<MailContent> mail = ArgumentCaptor.forClass(MailContent.class);
        verify(mailer, times(2)).send(mail.capture());
        assertThat(mail.getValue().subject()).endsWith("de madrugada: terminó con errores");
        assertThat(mail.getValue().paragraphs()).anySatisfy(p -> assertThat(p).startsWith("FPP: con 1 errores"));

        // Sin destinatario, no avisa.
        Mailer quiet = mock(Mailer.class);
        w.setNotify(quiet, null, "");
        w.run(true);
        verify(quiet, never()).send(any());
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
        // Los escudos de los equipos, cada uno una vez (los informes ya no los bajan).
        assertThat(site.logos).isNotEmpty().allSatisfy(l -> assertThat(l).endsWith(".png"));
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
    void withNothingPlayedSinceTheLastPassOnlyTheCalendarsAreRenewed() {
        CountingSite site = new CountingSite();
        MemoryStore store = new MemoryStore();
        // La pasada anterior fue después de todos los partidos del calendario.
        store.put(SidgadWarmup.lastSyncKey(Federation.FPP), "2026-12-01T08:00:00Z");

        SidgadWarmup.Result r = warmup(site, store).warm(Federation.FPP, 2026);

        // Ni actas ni fichas ni tablas ni estadísticas: lo guardado queda como está.
        assertThat(site.refreshed).hasSize(24).allSatisfy(k -> assertThat(k).startsWith("CALENDAR:"));
        assertThat(r.sheets()).isZero();
        assertThat(site.sheets.get()).isZero();
        assertThat(r.profiles()).isZero();
        assertThat(site.profiles).isEmpty();
    }

    @Test
    void theDailyPassOnlyAddsWhatWasPlayedSinceTheLastOne() {
        // Una pasada completa (todas las actas y fichas del año)...
        CountingSite all = new CountingSite();
        SidgadWarmup.Result full = warmup(all, new MemoryStore()).warm(Federation.FPP, 2026);
        // ...y la incremental, con la anterior del 10/10: solo los partidos de ese día (el calendario tiene 03/10 y 10/10).
        CountingSite site = new CountingSite();
        MemoryStore store = new MemoryStore();
        store.put(SidgadWarmup.lastSyncKey(Federation.FPP), "2026-10-10T08:00:00Z");

        SidgadWarmup.Result r = warmup(site, store).warm(Federation.FPP, 2026);

        // Las competiciones con partidos: calendario, tabla y estadísticas.
        assertThat(site.refreshed).hasSize(3 * 24);
        // Las actas de lo jugado desde el 10/10, bajadas de nuevo; menos que todas las del año.
        assertThat(r.sheets()).isPositive().isEqualTo(site.resheets.get()).isLessThan(full.sheets());
        // Las fichas de quienes jugaron esos partidos.
        assertThat(r.profiles()).isPositive().isLessThanOrEqualTo(full.profiles());
        assertThat(store.rows.get(SidgadWarmup.lastSyncKey(Federation.FPP)).body()).isEqualTo(START.toString());
    }

    @Test
    void aCompetitionThatEndedLongAgoIsNotTouched() {
        CountingSite site = new CountingSite();
        // El Apertura (693): su último partido, el 20/08.
        site.storedCalendar = com.madsports.Fixtures.read("cal_693.html");
        MemoryStore store = new MemoryStore();
        store.put(SidgadWarmup.lastSyncKey(Federation.FPP), "2026-10-19T08:00:00Z");

        SidgadWarmup.Result r = warmup(site, store).warm(Federation.FPP, 2026);

        assertThat(r.competitions()).isZero();
        assertThat(site.refreshed).isEmpty();
        // Sin pasada anterior (completa), se renueva igual.
        CountingSite first = new CountingSite();
        first.storedCalendar = site.storedCalendar;
        assertThat(warmup(first, new MemoryStore()).warm(Federation.FPP, 2026).competitions()).isEqualTo(24);
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
