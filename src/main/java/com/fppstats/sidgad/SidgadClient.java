package com.fppstats.sidgad;

import com.fppstats.config.FppProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pide fragmentos HTML a server2.sidgad.es. Es un sitio de terceros sin API pública:
 * se cachea todo y se espacian los pedidos para no cargarlo.
 */
@Component
public class SidgadClient {

    private static final Logger log = LoggerFactory.getLogger(SidgadClient.class);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; fpp-stats/0.1)";

    private final FppProperties.Sidgad props;
    private final Cache<String, String> dataCache;
    private final Cache<String, String> catalogCache;
    private final Cache<Integer, String> sheetCache;
    private final Object throttle = new Object();
    private long lastRequestNanos;

    public SidgadClient(FppProperties props) {
        this.props = props.sidgad();
        this.dataCache = Caffeine.newBuilder().expireAfterWrite(this.props.dataTtl()).maximumSize(500).build();
        this.catalogCache = Caffeine.newBuilder().expireAfterWrite(this.props.catalogTtl()).maximumSize(4).build();
        this.sheetCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(3000).build();
    }

    /** Contenido que se devuelve cuando SIDGAD responde 404: el dato no está publicado. */
    public static final String NOT_PUBLISHED = "";

    /*
     * Todos los métodos devuelven NOT_PUBLISHED si el fragmento da 404 (SIDGAD no lo publicó): el informe sigue
     * con lo que haya y los parsers devuelven listas vacías. Un 404 no se cachea, para reintentarlo en el próximo
     * informe. Cualquier otro error HTTP o de red sí corta el informe.
     */

    public String catalog() {
        return cachedIn(catalogCache, "ls", "cap_ls_1.php", Map.of("idm", "1"));
    }

    public String standings(int idc) {
        return cached("cap_clasif_idc_" + idc + "_1.php", Map.of("idc", String.valueOf(idc)));
    }

    public String calendar(int idc) {
        return cached("cap_cal_idc_" + idc + "_1.php", Map.of("idc", String.valueOf(idc)));
    }

    /** Las 13 columnas vienen siempre; {@code tipo_stats} solo cambia el orden. */
    public String playerStats(int idc) {
        return cached("cap_stats_1_" + idc + ".php",
                Map.of("idc", String.valueOf(idc), "tipo_stats", "goles", "site_lang", "es"));
    }

    /** Acta de un partido. Solo se piden actas de partidos ya jugados, que no cambian: cache de 24 h. */
    public String gameSheet(int idc, int idp) {
        return cachedIn(sheetCache, idp, "cap_gr_" + idp + "_1.php", Map.of(
                "idm", "1", "idc", String.valueOf(idc), "idp", String.valueOf(idp), "tab", "tab_ficha_resumen"));
    }

    private String cached(String file, Map<String, String> params) {
        return cachedIn(dataCache, file + new TreeMap<>(params), file, params);
    }

    private <K> String cachedIn(Cache<K, String> cache, K key, String file, Map<String, String> params) {
        String hit = cache.getIfPresent(key);
        if (hit != null) {
            return hit;
        }
        String body = post(file, params);
        if (!body.equals(NOT_PUBLISHED)) {
            cache.put(key, body);
        }
        return body;
    }

    private String post(String file, Map<String, String> params) {
        waitTurn();
        String url = props.baseUrl() + file;
        try {
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Origin", props.origin())
                    .referrer(props.origin() + "/")
                    .data(params)
                    .method(Connection.Method.POST)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .timeout((int) props.timeout().toMillis())
                    .maxBodySize(0)
                    .execute();
            if (res.statusCode() == 404) {
                log.info("SIDGAD no publicó {} (404), se continúa sin ese dato", file);
                return NOT_PUBLISHED;
            }
            if (res.statusCode() >= 400) {
                throw new SidgadException("SIDGAD respondió " + res.statusCode() + " para " + file);
            }
            String body = res.body();
            log.debug("SIDGAD {} -> {} bytes", file, body.length());
            if (body.isBlank()) {
                throw new SidgadException("SIDGAD devolvió una respuesta vacía para " + file);
            }
            return body;
        } catch (IOException e) {
            throw new SidgadException("No se pudo consultar " + file + ": " + e.getMessage(), e);
        }
    }

    private void waitTurn() {
        synchronized (throttle) {
            long wait = props.minInterval().toNanos() - (System.nanoTime() - lastRequestNanos);
            if (wait > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(wait));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SidgadException("Interrumpido esperando turno", e);
                }
            }
            lastRequestNanos = System.nanoTime();
        }
    }

    public static class SidgadException extends UncheckedIOException {
        public SidgadException(String message) {
            super(message, new IOException(message));
        }

        public SidgadException(String message, Exception cause) {
            super(message, cause instanceof IOException io ? io : new IOException(cause));
        }
    }
}
