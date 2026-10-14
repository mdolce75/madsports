package com.fppstats.sidgad;

import com.fppstats.config.FppProperties;
import com.fppstats.sidgad.SidgadClient.SidgadException;
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
            int status = file.startsWith("cap_clasif") ? 200 : file.startsWith("cap_stats") ? 500 : 404;
            byte[] body = (status == 200 ? "<table></table>" : "File not found.\n").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/cap/";
        client = new SidgadClient(new FppProperties(
                new FppProperties.Sidgad(base, "http://localhost", 3, Duration.ZERO, 3, Duration.ofMinutes(5),
                        Duration.ofMinutes(5), Duration.ofSeconds(5), List.of("PRIMERA"), List.of(2026)),
                new FppProperties.Ai(false, null, "m", "http://localhost", Duration.ofSeconds(1))));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void escudosConCacheYSinFallarSiNoEstan() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/cap/logos/";
        SidgadClient logos = new SidgadClient(new FppProperties(
                new FppProperties.Sidgad(base, "http://localhost", 3, Duration.ZERO, 3, Duration.ofMinutes(5),
                        Duration.ofMinutes(5), Duration.ofSeconds(5), List.of("PRIMERA"), List.of(2026)),
                new FppProperties.Ai(false, null, "m", "http://localhost", Duration.ofSeconds(1)))) {
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

    @Test
    void un404DevuelveVacioYNoSeCachea() {
        assertThat(client.calendar(1)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        assertThat(client.gameSheet(1, 99)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        assertThat(client.calendar(1)).isEqualTo(SidgadClient.NOT_PUBLISHED);
        // Se reintenta: puede publicarse más tarde.
        assertThat(hits.get("cap_cal_idc_1_1.php")).hasValue(2);
    }

    @Test
    void unaRespuestaCorrectaSeCachea() {
        assertThat(client.standings(1)).isEqualTo("<table></table>");
        assertThat(client.standings(1)).isEqualTo("<table></table>");
        assertThat(hits.get("cap_clasif_idc_1_1.php")).hasValue(1);
    }

    @Test
    void otrosErroresCortanElInforme() {
        assertThatThrownBy(() -> client.playerStats(1))
                .isInstanceOf(SidgadException.class)
                .hasMessageContaining("500");
    }

    @Test
    void pedidosEnParaleloConTope() throws Exception {
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
}
