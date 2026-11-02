package com.madsports.sidgad;

import com.madsports.sidgad.SidgadClient.SidgadException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    /** Cuántas veces más el calendario responde 429 (demasiados pedidos) antes de contestar normal. */
    final AtomicInteger busy = new AtomicInteger();
    /** El Retry-After de esos 429 (null: sin encabezado). */
    volatile String retryAfter;

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
}
