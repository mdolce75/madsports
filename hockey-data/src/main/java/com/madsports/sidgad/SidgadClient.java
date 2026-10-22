package com.madsports.sidgad;

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
import java.util.concurrent.Semaphore;

/**
 * Pide fragmentos HTML a server2.sidgad.es. Es un sitio de terceros sin API pública:
 * se cachea todo y se espacian los pedidos para no cargarlo.
 */
@Component
public class SidgadClient {

    private static final Logger log = LoggerFactory.getLogger(SidgadClient.class);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; madsports/0.1)";

    private final SidgadProperties props;
    private final Cache<String, String> dataCache;
    private final Cache<String, String> catalogCache;
    private final Cache<Integer, String> sheetCache;
    private final Cache<String, byte[]> logoCache;
    private final Object throttle = new Object();
    /** Pedidos simultáneos a SIDGAD como máximo ({@code rinkmetrics.sidgad.max-concurrency}). */
    private final Semaphore concurrency;
    private long lastRequestNanos;

    public SidgadClient(SidgadProperties props) {
        this.props = props;
        this.dataCache = Caffeine.newBuilder().expireAfterWrite(this.props.dataTtl()).maximumSize(500).build();
        this.catalogCache = Caffeine.newBuilder().expireAfterWrite(this.props.catalogTtl()).maximumSize(4).build();
        this.sheetCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(3000).build();
        this.logoCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(100).build();
        this.concurrency = new Semaphore(Math.max(1, this.props.maxConcurrency()), true);
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

    /** Página principal del portal (GET): trae la lista de temporadas con su año (selector "Temporada"). */
    public String portal() {
        String hit = catalogCache.getIfPresent("portal");
        if (hit != null) {
            return hit;
        }
        String body = fetch(props.origin() + "/", Connection.Method.GET, Map.of(), "portal");
        if (!body.equals(NOT_PUBLISHED)) {
            catalogCache.put("portal", body);
        }
        return body;
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

    /**
     * Ficha de temporada de un jugador ({@code seasonId} = id_temp): sus partidos de cada competición, incluidos los
     * que no tienen acta publicada, con goles, libres, penales, azules y rojas.
     */
    public String playerSeason(int playerId, int seasonId) {
        return cached("profiles/cap_profileseason_" + playerId + "_1_" + seasonId + ".php",
                Map.of("idm", "1", "id_player", String.valueOf(playerId)));
    }

    /** Carpeta pública de escudos de SIDGAD (la misma que usan las tablas y el calendario del sitio). */
    static final String LOGOS_URL = "https://sidgad.cloud/cap/images/logos_clubes/";
    private static final java.util.regex.Pattern LOGO_FILE = java.util.regex.Pattern.compile("\\d+(?:_\\d+)?\\.png");

    /**
     * Escudo de un club ({@code file} como figura en teams_array, p. ej. "22_10.png"). El escudo es un adorno: si no
     * está o falla la descarga se devuelve {@code null} y el informe sale sin él. Cache de 24 h.
     */
    public byte[] clubLogo(String file) {
        if (file == null || !LOGO_FILE.matcher(file).matches()) {
            return null;
        }
        byte[] hit = logoCache.getIfPresent(file);
        if (hit != null) {
            return hit;
        }
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            waitTurn();
            Connection.Response res = Jsoup.connect(logosUrl() + file)
                    .userAgent(USER_AGENT)
                    .referrer(props.origin() + "/")
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .timeout((int) props.timeout().toMillis())
                    .maxBodySize(2 * 1024 * 1024)
                    .execute();
            if (res.statusCode() != 200) {
                log.info("Escudo {} no disponible ({})", file, res.statusCode());
                return null;
            }
            byte[] body = res.bodyAsBytes();
            logoCache.put(file, body);
            return body;
        } catch (IOException | RuntimeException e) {
            log.info("No se pudo bajar el escudo {}: {}", file, e.getMessage());
            return null;
        } finally {
            concurrency.release();
        }
    }

    /** URL base de los escudos (los tests la reemplazan por un servidor local). */
    protected String logosUrl() {
        return LOGOS_URL;
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
        return fetch(props.baseUrl() + file, Connection.Method.POST, params, file);
    }

    private String fetch(String url, Connection.Method method, Map<String, String> params, String file) {
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SidgadException("Interrumpido esperando turno", e);
        }
        try {
            return doFetch(url, method, params, file);
        } finally {
            concurrency.release();
        }
    }

    private String doFetch(String url, Connection.Method method, Map<String, String> params, String file) {
        waitTurn();
        try {
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Origin", props.origin())
                    .referrer(props.origin() + "/")
                    .data(params)
                    .method(method)
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
