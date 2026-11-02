package com.madsports.sidgad;

import com.madsports.sidgad.SidgadClient.SidgadException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contra un servidor local que imita a SIDGAD: 404 = dato no publicado, otros errores cortan. */
class SidgadClientTest {

    HttpServer server;
    final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    SidgadClient client;
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    /** Respuesta de SIDGAD al catálogo: 200 con {@link #catalogBody}, o el error que se ponga. */
    final AtomicInteger catalogStatus = new AtomicInteger(200);
    volatile String catalogBody = "<a class='idc_master' id='779'>v1</a>";
    /** Cuántas veces más el calendario responde 429 (demasiados pedidos) antes de contestar normal. */
    final AtomicInteger busy = new AtomicInteger();
    /** El Retry-After de esos 429 (null: sin encabezado). */
    volatile String retryAfter;

    /** Un almacén de copias en memoria, como el de la base. */
    static class MemorySnapshotStore implements SnapshotStore {
        final Map<String, Stored> rows = new ConcurrentHashMap<>();
        Instant now = Instant.parse("2026-11-02T08:00:00Z");

        @Override
        public Optional<Stored> get(String key) {
            return Optional.ofNullable(rows.get(key));
        }

        @Override
        public boolean put(String key, String body) {
            Stored old = rows.get(key);
            boolean changed = old == null || !old.body().equals(body);
            rows.put(key, new Stored(body, now, changed ? now : old.changedAt()));
            return changed;
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cap/", ex -> {
            String file = ex.getRequestURI().getPath().substring("/cap/".length());
            hits.computeIfAbsent(file, k -> new AtomicInteger()).incrementAndGet();
            if (file.equals("logos/22_10.png")) {
                byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
                ex.sendResponseHeaders(200, png.length);
                ex.getResponseBody().write(png);
                ex.close();
                return;
            }
            if (file.startsWith("cap_gr_10")) {
                // Ficha lenta: mide cuántos pedidos hay en curso a la vez.
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                byte[] body = "<html></html>".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
                return;
            }
            if (file.startsWith("profiles/") || file.startsWith("cap_gr_2")) {
                // Ficha de temporada o acta: cada respuesta dice cuántas veces se pidió.
                byte[] body = ("<p>" + hits.get(file).get() + "</p>").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
                return;
            }
            if (file.startsWith("cap_cal") && busy.getAndDecrement() > 0) {
                if (retryAfter != null) {
                    ex.getResponseHeaders().add("Retry-After", retryAfter);
                }
                byte[] body = "Too Many Requests".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(429, body.length);
                ex.getResponseBody().write(body);
                ex.close();
                return;
            }
            if (file.equals("cap_ls_1.php")) {
                int code = catalogStatus.get();
                byte[] body = (code == 200 ? catalogBody : "error").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(code, body.length);
                ex.getResponseBody().write(body);
                ex.close();
                return;
            }
            int status = file.startsWith("cap_clasif") ? 200 : file.startsWith("cap_stats") ? 500 : 404;
            byte[] body = (status == 200 ? "<table></table>" : "File not found.\n").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/cap/";
        client = new SidgadClient(new SidgadProperties(base, "http://localhost", Duration.ZERO, 3, Duration.ofMinutes(5),
                        Duration.ofMinutes(5), Duration.ofSeconds(5), List.of(2026), 1));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void crestsAreCachedAndDoNotFailIfMissing() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/cap/logos/";
        SidgadClient logos = new SidgadClient(new SidgadProperties(base, "http://localhost", Duration.ZERO, 3, Duration.ofMinutes(5),
                        Duration.ofMinutes(5), Duration.ofSeconds(5), List.of(2026), 1)) {
            @Override
            protected String logosUrl() {
                return base;
            }
        };
        assertThat(logos.clubLogo("22_10.png")).hasSize(4);
        assertThat(logos.clubLogo("22_10.png")).hasSize(4);
        assertThat(hits.get("logos/22_10.png")).hasValue(1);
        assertThat(logos.clubLogo("99.png")).isNull();
        // Solo nombres de archivo de escudo: nada de rutas.
        assertThat(logos.clubLogo("../cap_ls_1.php")).isNull();
        assertThat(hits).doesNotContainKey("cap_ls_1.php");
    }

    /** Un almacén de actas en memoria, como el de la base. */
    static class MapStore implements SheetStore {
        final Map<Integer, String> saved = new ConcurrentHashMap<>();

        @Override
        public java.util.Optional<String> get(int idp) {
            return java.util.Optional.ofNullable(saved.get(idp));
        }

        @Override
        public void put(int idp, String html) {
            saved.put(idp, html);
        }
    }

    SidgadClient newClient() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/cap/";
        return new SidgadClient(new SidgadProperties(base, "http://localhost", Duration.ZERO, 3, Duration.ofMinutes(5),
                Duration.ofMinutes(5), Duration.ofSeconds(5), List.of(2026), 1));
    }

    @Test
    void gameSheetsAreSavedAndReadFromTheStoreAfterARestart() {
        MapStore store = new MapStore();
        client.setSheetStore(store);
        assertThat(client.gameSheet(1, 2001)).isEqualTo("<p>1</p>");
        assertThat(store.saved).containsEntry(2001, "<p>1</p>");
        // Otra instancia de la app (sin nada en memoria), con la misma base: no vuelve a pedirla a SIDGAD.
        SidgadClient restarted = newClient();
        restarted.setSheetStore(store);
        assertThat(restarted.gameSheet(1, 2001)).isEqualTo("<p>1</p>");
        assertThat(hits.get("cap_gr_2001_1.php")).hasValue(1);
        // Si el almacén dice que hay que bajarla de nuevo (vacío), se baja y se actualiza.
        store.saved.clear();
        SidgadClient again = newClient();
        again.setSheetStore(store);
        assertThat(again.gameSheet(1, 2001)).isEqualTo("<p>2</p>");
        assertThat(store.saved).containsEntry(2001, "<p>2</p>");
        // Lo no publicado (404) no se guarda.
        assertThat(client.gameSheet(1, 99)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        assertThat(store.saved).doesNotContainKey(99);
    }

    @Test
    void aSeasonProfileIsReusedOnlyIfFetchedSinceTheAskedMoment() {
        java.time.Instant before = java.time.Instant.now().minusSeconds(60);
        assertThat(client.playerSeason(7, 1, before)).isEqualTo("<p>1</p>");
        // Bajada después de "before": sirve.
        assertThat(client.playerSeason(7, 1, before)).isEqualTo("<p>1</p>");
        // Se pide una más nueva (el club jugó después): se baja de nuevo.
        assertThat(client.playerSeason(7, 1, java.time.Instant.now().plusSeconds(1))).isEqualTo("<p>2</p>");
        assertThat(hits.get("profiles/cap_profileseason_7_1_1.php")).hasValue(2);
    }

    @Test
    void a404ReturnsEmptyAndIsNotCached() {
        assertThat(client.calendar(1)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        assertThat(client.gameSheet(1, 99)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        assertThat(client.calendar(1)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        // Se reintenta: puede publicarse más tarde.
        assertThat(hits.get("cap_cal_idc_1_1.php")).hasValue(2);
    }

    @Test
    void aSuccessfulResponseIsCached() {
        assertThat(client.standings(1)).isEqualTo("<table></table>");
        assertThat(client.standings(1)).isEqualTo("<table></table>");
        assertThat(hits.get("cap_clasif_idc_1_1.php")).hasValue(1);
    }

    @Test
    void otherErrorsStopTheReport() {
        assertThatThrownBy(() -> client.playerStats(1))
                .isInstanceOf(SidgadException.class)
                .hasMessageContaining("500");
    }

    @Test
    void parallelRequestsWithACap() throws Exception {
        // Tope de 3 pedidos simultáneos (configuración del test): 12 fichas lentas pedidas a la vez.
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int idp = 1000 + i;
                futures.add(pool.submit(() -> client.gameSheet(1, idp)));
            }
            for (var f : futures) {
                assertThat(f.get()).isEqualTo("<html></html>");
            }
        }
        assertThat(maxInFlight.get()).isGreaterThan(1).isLessThanOrEqualTo(3);
    }

    /** Un cliente nuevo (como después de reiniciar la app) con el almacén y la hora dados; catálogo vigente 5 min. */
    private SidgadClient restarted(MemorySnapshotStore store, Instant now) {
        SidgadClient c = new SidgadClient(new SidgadProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/cap/", "http://localhost", Duration.ZERO, 3,
                Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofSeconds(5), List.of(2026), 1));
        c.setSnapshotStore(store);
        c.setClock(Clock.fixed(now, ZoneOffset.UTC));
        return c;
    }

    @Test
    void aTooManyRequestsPausesAndRetries() {
        client.retryBackoff(List.of(Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMillis(50)));
        busy.set(2);
        long start = System.nanoTime();

        // Dos 429 y después la respuesta de siempre (404: no publicado).
        assertThat(client.calendar(7)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        assertThat(hits.get("cap_cal_idc_7_1.php")).hasValue(3);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
    }

    @Test
    void ifSidgadKeepsSayingTooManyRequestsTheReportStops() {
        client.retryBackoff(List.of(Duration.ofMillis(10), Duration.ofMillis(10)));
        busy.set(10);

        assertThatThrownBy(() -> client.calendar(8)).isInstanceOf(SidgadException.class).hasMessageContaining("429");
        assertThat(hits.get("cap_cal_idc_8_1.php")).hasValue(3);
    }

    @Test
    void retryAfterInSecondsUpToAMinute() {
        assertThat(SidgadClient.retryAfter("30")).contains(Duration.ofSeconds(30));
        assertThat(SidgadClient.retryAfter(" 0 ")).contains(Duration.ZERO);
        assertThat(SidgadClient.retryAfter("600")).contains(SidgadClient.MAX_PAUSE);
        assertThat(SidgadClient.retryAfter(null)).isEmpty();
        assertThat(SidgadClient.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isEmpty();
    }

    @Test
    void theRetryAfterOfSidgadIsRespected() {
        // Las esperas propias serían largas: manda el Retry-After de SIDGAD (0 s).
        client.retryBackoff(List.of(Duration.ofSeconds(30)));
        busy.set(1);
        retryAfter = "0";
        long start = System.nanoTime();

        assertThat(client.calendar(9)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        assertThat(hits.get("cap_cal_idc_9_1.php")).hasValue(2);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    /** Un cliente cuya conexión HTTP/2 rechaza los primeros {@code refusals} pedidos por "too many concurrent streams". */
    private SidgadClient refusingStreams(int refusals) {
        AtomicInteger left = new AtomicInteger(refusals);
        SidgadClient c = new SidgadClient(new SidgadProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/cap/", "http://localhost", Duration.ZERO, 3,
                Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofSeconds(5), List.of(2026), 1)) {
            @Override
            org.jsoup.Connection.Response request(String url, org.jsoup.Connection.Method method,
                                                  Map<String, String> params, String file) {
                if (left.getAndDecrement() > 0) {
                    throw new SidgadException("No se pudo consultar " + file + ": too many concurrent streams",
                            new java.io.IOException("too many concurrent streams"));
                }
                return super.request(url, method, params, file);
            }
        };
        c.retryBackoff(List.of(Duration.ofMillis(10), Duration.ofMillis(10)));
        return c;
    }

    @Test
    void tooManyConcurrentStreamsAlsoPausesAndRetries() {
        assertThat(refusingStreams(2).standings(3)).isEqualTo("<table></table>");
        assertThat(hits.get("cap_clasif_idc_3_1.php")).hasValue(1);

        assertThatThrownBy(() -> refusingStreams(5).standings(4)).isInstanceOf(SidgadException.class)
                .hasMessageContaining("too many concurrent streams");
        assertThat(hits).doesNotContainKey("cap_clasif_idc_4_1.php");
    }

    @Test
    void otherNetworkErrorsAreNotRetried() {
        assertThat(SidgadClient.tooManyStreams(new SidgadException("x", new java.io.IOException("Connection reset"))))
                .isFalse();
        assertThat(SidgadClient.tooManyStreams(new SidgadException("x",
                new java.io.IOException("too many concurrent streams")))).isTrue();
    }

    // --- Copias de los datos de cada competición y de las fichas de temporada (vigentes 5 min en estos tests).

    @Test
    void theDateOfTheDataIsTheOldestSavedCopyOfThoseCompetitions() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        store.put(SidgadClient.Fragment.CALENDAR.key(30), "<table></table>");
        store.now = store.now.plusSeconds(3600);
        store.put(SidgadClient.Fragment.STANDINGS.key(31), "<table></table>");
        SidgadClient c = restarted(store, store.now);

        assertThat(c.oldestCopy(List.of(30, 31))).contains(Instant.parse("2026-11-02T08:00:00Z"));
        assertThat(c.oldestCopy(List.of(31))).contains(Instant.parse("2026-11-02T09:00:00Z"));
        assertThat(c.oldestCopy(List.of(32))).isEmpty();
    }

    @Test
    void withSavedCopiesNothingIsAskedToSidgad() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        store.put(SidgadClient.Fragment.STANDINGS.key(5), "<table>tabla</table>");
        store.put(SidgadClient.Fragment.CALENDAR.key(5), "<table>calendario</table>");
        store.put(SidgadClient.Fragment.STATS.key(5), "<table>estadísticas</table>");
        store.put(SidgadClient.profileKey(10, 15), "<p>ficha</p>");
        SidgadClient c = restarted(store, store.now.plusSeconds(60));

        assertThat(c.standings(5)).isEqualTo("<table>tabla</table>");
        assertThat(c.calendar(5)).isEqualTo("<table>calendario</table>");
        assertThat(c.playerStats(5)).isEqualTo("<table>estadísticas</table>");
        assertThat(c.playerSeason(10, 15)).isEqualTo("<p>ficha</p>");
        assertThat(hits).isEmpty();
    }

    @Test
    void withoutACopyItIsDownloadedAndSaved() {
        MemorySnapshotStore store = new MemorySnapshotStore();

        assertThat(restarted(store, store.now).standings(6)).isEqualTo("<table></table>");
        assertThat(store.rows.get(SidgadClient.Fragment.STANDINGS.key(6)).body()).isEqualTo("<table></table>");
        // Lo no publicado (404) no se guarda.
        assertThat(restarted(store, store.now).calendar(6)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        assertThat(store.rows).doesNotContainKey(SidgadClient.Fragment.CALENDAR.key(6));
    }

    @Test
    void anExpiredDataCopyIsDownloadedAgainAndIfSidgadFailsTheOldOneIsUsed() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        store.put(SidgadClient.Fragment.STANDINGS.key(7), "<table>vieja</table>");
        store.put(SidgadClient.Fragment.STATS.key(7), "<table>vieja</table>");
        Instant later = store.now.plus(Duration.ofMinutes(10));

        assertThat(restarted(store, later).standings(7)).isEqualTo("<table></table>");
        // Las estadísticas dan 500 en este SIDGAD: queda la copia vieja; sin copia, el error.
        assertThat(restarted(store, later).playerStats(7)).isEqualTo("<table>vieja</table>");
        assertThatThrownBy(() -> restarted(store, later).playerStats(8)).isInstanceOf(SidgadException.class);
    }

    @Test
    void inStoredModeOnlyTheSyncGoesToSidgad() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        store.put(SidgadClient.Fragment.STANDINGS.key(40), "<table>de hace días</table>");
        store.put(SidgadClient.profileKey(41, 15), "<p>ficha vieja</p>");
        // Mucho después de la vigencia de las copias: igual se usan, sin ir a SIDGAD.
        SidgadClient c = restarted(store, store.now.plus(Duration.ofDays(5)));
        c.setReadMode("stored");

        assertThat(c.standings(40)).isEqualTo("<table>de hace días</table>");
        assertThat(c.playerSeason(41, 15)).isEqualTo("<p>ficha vieja</p>");
        // Lo que no está guardado no se baja: falta la sincronización (los datos), o no hay (actas, fichas, escudos).
        assertThatThrownBy(() -> c.calendar(40)).isInstanceOf(SidgadClient.NotSyncedException.class)
                .hasMessage(SidgadClient.NotSyncedException.MESSAGE);
        assertThatThrownBy(c::catalog).isInstanceOf(SidgadClient.NotSyncedException.class);
        assertThat(c.gameSheet(40, 2040)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        assertThat(c.playerSeason(42, 15)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        assertThat(c.clubLogo("22_10.png")).isNull();
        assertThat(hits).isEmpty();

        // La sincronización sí va a SIDGAD, y deja la copia para los informes.
        c.syncing(() -> assertThat(c.refresh(SidgadClient.Fragment.STANDINGS, 43)).isTrue());
        assertThat(c.standings(43)).isEqualTo("<table></table>");
        assertThat(hits.get("cap_clasif_idc_43_1.php")).hasValue(1);
    }

    @Test
    void whatSidgadDidNotPublishIsNotedSoTheReportsDoNotWaitForIt() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        SidgadClient c = restarted(store, store.now);
        c.setReadMode("stored");

        // El calendario da 404 en este SIDGAD: sin copia anterior, queda anotado como no publicado.
        c.syncing(() -> assertThat(c.refresh(SidgadClient.Fragment.CALENDAR, 44)).isFalse());
        assertThat(c.calendar(44)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        // Con una copia anterior, un 404 no la pisa.
        store.put(SidgadClient.Fragment.CALENDAR.key(45), "<table>anterior</table>");
        c.syncing(() -> c.refresh(SidgadClient.Fragment.CALENDAR, 45));
        assertThat(store.rows.get(SidgadClient.Fragment.CALENDAR.key(45)).body()).isEqualTo("<table>anterior</table>");
    }

    @Test
    void theSyncSavesTheCrestsAndTheReportsReadThemFromTheStore() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/cap/logos/";
        MemorySnapshotStore store = new MemorySnapshotStore();
        java.util.function.Supplier<SidgadClient> fresh = () -> {
            SidgadClient c = new SidgadClient(new SidgadProperties(base, "http://localhost", Duration.ZERO, 3,
                    Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofSeconds(5), List.of(2026), 1)) {
                @Override
                protected String logosUrl() {
                    return base;
                }
            };
            c.setSnapshotStore(store);
            c.setReadMode("stored");
            return c;
        };
        SidgadClient sync = fresh.get();
        sync.syncing(() -> assertThat(sync.clubLogo("22_10.png")).hasSize(4));

        // Otro arranque, sin memoria: el escudo sale de la base.
        assertThat(fresh.get().clubLogo("22_10.png")).containsExactly(0x89, 'P', 'N', 'G');
        assertThat(hits.get("logos/22_10.png")).hasValue(1);
    }

    @Test
    void inLiveModeTheSavedCopiesAreNotRead() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        store.put(SidgadClient.Fragment.STANDINGS.key(9), "<table>guardada</table>");
        SidgadClient c = restarted(store, store.now);
        c.setReadMode("live");

        assertThat(c.standings(9)).isEqualTo("<table></table>");
        assertThat(hits.get("cap_clasif_idc_9_1.php")).hasValue(1);
    }

    @Test
    void refreshDownloadsEvenAValidCopyAndSaysIfItChanged() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        store.put(SidgadClient.Fragment.STANDINGS.key(11), "<table>antes</table>");
        SidgadClient c = restarted(store, store.now);

        assertThat(c.refresh(SidgadClient.Fragment.STANDINGS, 11)).isTrue();
        assertThat(c.standings(11)).isEqualTo("<table></table>");
        assertThat(c.refresh(SidgadClient.Fragment.STANDINGS, 11)).isFalse();
        assertThat(hits.get("cap_clasif_idc_11_1.php")).hasValue(2);
        // No publicado: queda la copia que hubiera.
        assertThat(c.refresh(SidgadClient.Fragment.CALENDAR, 11)).isFalse();
    }

    @Test
    void aSavedProfileServesIfItIsNewerThanAskedAndIsSavedWhenDownloaded() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        store.put(SidgadClient.profileKey(20, 15), "<p>guardada</p>");
        SidgadClient c = restarted(store, store.now.plusSeconds(60));

        assertThat(c.playerSeason(20, 15, store.now.minusSeconds(1))).isEqualTo("<p>guardada</p>");
        // Pedida desde después de la copia (como la precarga): se baja de nuevo y se guarda.
        assertThat(restarted(store, store.now.plusSeconds(60)).playerSeason(20, 15, store.now.plusSeconds(30)))
                .isEqualTo("<p>1</p>");
        assertThat(store.rows.get(SidgadClient.profileKey(20, 15)).body()).isEqualTo("<p>1</p>");
    }

    @Test
    void theCatalogIsSavedAndARestartDoesNotDownloadItAgain() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        assertThat(restarted(store, store.now).catalog()).contains("v1");
        assertThat(store.rows.get(SidgadClient.CATALOG).body()).contains("v1");

        catalogBody = "<a class='idc_master' id='779'>v2</a>";
        assertThat(restarted(store, store.now.plusSeconds(60)).catalog()).contains("v1");
        assertThat(hits.get("cap_ls_1.php")).hasValue(1);
    }

    @Test
    void anExpiredCopyIsDownloadedAgain() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        restarted(store, store.now).catalog();
        catalogBody = "<a class='idc_master' id='779'>v2</a>";
        store.now = store.now.plus(Duration.ofMinutes(10));

        assertThat(restarted(store, store.now).catalog()).contains("v2");
        assertThat(store.rows.get(SidgadClient.CATALOG).fetchedAt()).isEqualTo(store.now);
    }

    @Test
    void ifSidgadFailsTheSavedCopyIsUsedEvenIfOld() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        restarted(store, store.now).catalog();
        catalogStatus.set(500);

        assertThat(restarted(store, store.now.plus(Duration.ofDays(3))).catalog()).contains("v1");
        // Sin copia guardada, el error llega al informe.
        assertThatThrownBy(() -> restarted(new MemorySnapshotStore(), store.now).catalog())
                .isInstanceOf(SidgadException.class);
    }

    @Test
    void theDailyRefreshDownloadsEvenAValidCopyAndSaysWhatChanged() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        SidgadClient c = restarted(store, store.now);
        catalogBody = "<a class='idc_master id_fede_3 temp_15' id='779'>"
                + "<span class='nombre_competicion_listado'>TORNEO APERTURA PRIMERA MASCULINO (FPP)</span></a>"
                + "<input id='teams_array_779' value='22,101,VELEZ,VELEZ'>";
        c.catalog();
        catalogBody = catalogBody.replace("value='22,101,VELEZ,VELEZ'", "value='22,101,VELEZ,VELEZ;30,102,CAVS,CAVS'");

        SidgadClient.CatalogRefresh r = c.refreshCatalog();

        assertThat(hits.get("cap_ls_1.php")).hasValue(2);
        assertThat(r.downloaded()).isFalse();   // el portal (otro sitio) no responde en el test
        assertThat(r.changes()).containsExactly("En TORNEO APERTURA PRIMERA MASCULINO (FPP): entra CAVS");
        assertThat(c.catalog()).contains("CAVS");
        assertThat(store.rows.get(SidgadClient.CATALOG).body()).contains("CAVS");
    }

    @Test
    void ifTheDailyRefreshFailsTheCopyStays() {
        MemorySnapshotStore store = new MemorySnapshotStore();
        SidgadClient c = restarted(store, store.now);
        c.catalog();
        catalogStatus.set(503);

        SidgadClient.CatalogRefresh r = c.refreshCatalog();

        assertThat(r.downloaded()).isFalse();
        assertThat(r.changes()).isEmpty();
        assertThat(c.catalog()).contains("v1");
        assertThat(store.rows.get(SidgadClient.CATALOG).body()).contains("v1");
    }
}
