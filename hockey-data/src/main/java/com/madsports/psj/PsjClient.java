package com.madsports.psj;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.madsports.sidgad.SheetStore;
import com.madsports.source.SourceException;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Pide JSON a la API pública de gestiondepsj.com.ar (San Juan y Entre Ríos). Es un sitio de terceros: se cachea todo y
 * se espacian los pedidos. Un 404 es "no publicado" ({@link #NOT_PUBLISHED}) y no se cachea; cualquier otro error HTTP o
 * de red es una {@link PsjException}.
 */
@Component
public class PsjClient {

    private static final Logger log = LoggerFactory.getLogger(PsjClient.class);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; madsports/0.1)";

    /** Contenido que se devuelve cuando la API responde 404. */
    public static final String NOT_PUBLISHED = "";

    private final PsjProperties props;
    private final Cache<String, String> catalogCache;
    private final Cache<String, String> dataCache;
    /** Partidos terminados (detalle con planillas + eventos): en memoria los últimos; el resto, en {@link SheetStore}. */
    private final Cache<Integer, String> sheetCache;
    private final Cache<String, byte[]> logoCache;
    private volatile SheetStore sheetStore;
    /** Un lock (no synchronized): el turno duerme adentro y, en Java 21, dormir en un synchronized fija el hilo virtual. */
    private final ReentrantLock throttle = new ReentrantLock();
    private final Semaphore concurrency;
    private long lastRequestNanos;

    public PsjClient(PsjProperties props) {
        this.props = props;
        this.catalogCache = Caffeine.newBuilder().expireAfterWrite(props.catalogTtl()).maximumSize(50).build();
        this.dataCache = Caffeine.newBuilder().expireAfterWrite(props.dataTtl()).maximumSize(2000).build();
        this.sheetCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(2000).build();
        this.logoCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(200).build();
        this.concurrency = new Semaphore(Math.max(1, props.maxConcurrency()), true);
    }

    /** Los partidos se guardan de forma permanente en este almacén (lo registra la app; sin él, solo en memoria). */
    @Autowired(required = false)
    public void setSheetStore(SheetStore store) {
        this.sheetStore = store;
    }

    /** Torneos de una federación ({@code federation_id}: FSP = 1, UEHP = 1000). */
    public String tournaments(int federationId) {
        return cached(catalogCache, "tournaments?federation_id=" + federationId);
    }

    /** Torneos de un comité ({@code committee_id}: CNTHsP = 1, el de la Copa Nacional de Clubes). */
    public String committeeTournaments(int committeeId) {
        return cached(catalogCache, "tournaments?committee_id=" + committeeId);
    }

    public String standings(int tournamentId) {
        return cached(dataCache, "tournaments/" + tournamentId + "/standings");
    }

    /** Fechas del torneo ({@code tournaments/{id}/matches} viene vacío: los partidos van por fase). */
    public String phases(int tournamentId) {
        return cached(catalogCache, "tournaments/" + tournamentId + "/phases");
    }

    public String phaseMatches(int phaseId) {
        return cached(dataCache, "phases/" + phaseId + "/matches");
    }

    /** Eventos de un jugador en un torneo (para su ficha). */
    public String playerEvents(int playerId, int tournamentId) {
        return cached(dataCache, "players/" + playerId + "/events_in_tournament/" + tournamentId);
    }

    /**
     * Un partido: el detalle de {@code matches/{id}} (con {@code home_roster} y {@code away_roster}) más sus eventos
     * ({@code matches/{id}/events}) en {@code events}. Los terminados no cambian: se guardan en {@link SheetStore} con la
     * clave {@code -id} (negativa, para no cruzarse con las actas de SIDGAD).
     */
    public String match(int matchId) {
        String hit = sheetCache.getIfPresent(matchId);
        if (hit != null) {
            return hit;
        }
        SheetStore store = sheetStore;
        Optional<String> saved = Optional.empty();
        if (store != null) {
            try {
                saved = store.get(-matchId);
            } catch (RuntimeException e) {
                log.warn("No se pudo leer el partido guardado {}: {}", matchId, e.getMessage());
            }
        }
        if (saved.isPresent()) {
            sheetCache.put(matchId, saved.get());
            return saved.get();
        }
        String detail = get("matches/" + matchId);
        if (detail.equals(NOT_PUBLISHED)) {
            return NOT_PUBLISHED;
        }
        String events = get("matches/" + matchId + "/events");
        String body = PsjParser.withEvents(detail, events.equals(NOT_PUBLISHED) ? "[]" : events);
        if (PsjParser.completed(body)) {
            sheetCache.put(matchId, body);
            if (store != null) {
                try {
                    store.put(-matchId, body);
                } catch (RuntimeException e) {
                    log.warn("No se pudo guardar el partido {}: {}", matchId, e.getMessage());
                }
            }
        }
        return body;
    }

    private static final Pattern LOGO_FILE = Pattern.compile("uploads/clubs/[A-Za-z0-9._-]+\\.(?:png|jpe?g|webp)");

    /**
     * Escudo de un club ({@code logo_url}, p. ej. "uploads/clubs/logo-1.png"). Es un adorno: si no está o falla la
     * descarga se devuelve {@code null} y el informe sale sin él. Cache de 24 h.
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
            byte[] body = withTurn(() -> downloadBytes(props.origin() + file));
            if (body != null) {
                logoCache.put(file, body);
            }
            return body;
        } catch (RuntimeException e) {
            log.info("No se pudo bajar el escudo {}: {}", file, e.getMessage());
            return null;
        }
    }

    private String cached(Cache<String, String> cache, String path) {
        String hit = cache.getIfPresent(path);
        if (hit != null) {
            return hit;
        }
        String body = get(path);
        if (!body.equals(NOT_PUBLISHED)) {
            cache.put(path, body);
        }
        return body;
    }

    private String get(String path) {
        return withTurn(() -> download(path));
    }

    /** El JSON de {@code baseUrl + path}; {@link #NOT_PUBLISHED} si da 404 (los tests lo reemplazan por fixtures). */
    protected String download(String path) {
        try {
            Connection.Response res = connect(props.baseUrl() + path).execute();
            if (res.statusCode() == 404) {
                log.info("gestiondepsj no publicó {} (404), se continúa sin ese dato", path);
                return NOT_PUBLISHED;
            }
            if (res.statusCode() >= 400) {
                throw new PsjException("gestiondepsj respondió " + res.statusCode() + " para " + path);
            }
            String body = res.body();
            if (body.isBlank()) {
                throw new PsjException("gestiondepsj devolvió una respuesta vacía para " + path);
            }
            log.debug("gestiondepsj {} -> {} bytes", path, body.length());
            return body;
        } catch (IOException e) {
            throw new PsjException("No se pudo consultar " + path + ": " + e.getMessage(), e);
        }
    }

    /** Un archivo del sitio (escudos); null si no da 200. */
    protected byte[] downloadBytes(String url) {
        try {
            Connection.Response res = connect(url).maxBodySize(2 * 1024 * 1024).execute();
            return res.statusCode() == 200 ? res.bodyAsBytes() : null;
        } catch (IOException e) {
            throw new PsjException("No se pudo bajar " + url + ": " + e.getMessage(), e);
        }
    }

    private Connection connect(String url) {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .referrer(props.origin())
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout((int) props.timeout().toMillis())
                .maxBodySize(0);
    }

    private <T> T withTurn(Supplier<T> request) {
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PsjException("Interrumpido esperando turno", e);
        }
        try {
            waitTurn();
            return request.get();
        } finally {
            concurrency.release();
        }
    }

    private void waitTurn() {
        throttle.lock();
        try {
            long wait = props.minInterval().toNanos() - (System.nanoTime() - lastRequestNanos);
            if (wait > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(wait));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new PsjException("Interrumpido esperando turno", e);
                }
            }
            lastRequestNanos = System.nanoTime();
        } finally {
            throttle.unlock();
        }
    }

    /** Error de gestiondepsj (HTTP o de red). */
    public static class PsjException extends SourceException {
        public PsjException(String message) {
            super(message);
        }

        public PsjException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
