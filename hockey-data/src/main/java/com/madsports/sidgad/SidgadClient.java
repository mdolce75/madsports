package com.madsports.sidgad;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
    /** Fichas de temporada de jugadores, con la hora en que se bajaron (ver {@link #playerSeason(int, int, Instant)}). */
    private final Cache<String, Fetched> profileCache;
    /** Las actas guardadas de forma permanente (null en los tests sin base). */
    private volatile SheetStore sheetStore;
    /** La copia guardada del catálogo (null en los tests sin base). */
    private volatile SnapshotStore snapshotStore;
    private Clock clock = Clock.systemUTC();
    private final Cache<String, byte[]> logoCache;
    /** Un lock (no synchronized): el turno duerme adentro y, en Java 21, dormir en un synchronized fija el hilo virtual. */
    private final ReentrantLock throttle = new ReentrantLock();
    /** Pedidos simultáneos a SIDGAD como máximo ({@code madsports.sidgad.max-concurrency}). */
    private final Semaphore concurrency;
    /** Los del informe del club completo: {@code club-factor} veces más, en su propio cupo. */
    private final Semaphore clubConcurrency;
    /**
     * Marca los pedidos del informe del club completo. Es heredable: los hilos virtuales que ese informe abre para
     * leer en paralelo también la llevan.
     */
    private static final InheritableThreadLocal<Boolean> CLUB_REPORT = new InheritableThreadLocal<>();
    private long lastRequestNanos;
    /**
     * Esperas ante un 429 (SIDGAD pide bajar el ritmo), una por reintento; si responde con Retry-After, se espera eso
     * (hasta {@link #MAX_PAUSE}). Agotados los reintentos, el 429 corta el informe como cualquier error.
     */
    private List<Duration> backoff = List.of(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(20));
    static final Duration MAX_PAUSE = Duration.ofSeconds(60);
    /** Hasta cuándo (System.nanoTime) no sale ningún pedido a SIDGAD: después de un 429, la pausa vale para todos. */
    private final AtomicLong pausedUntil = new AtomicLong(System.nanoTime());

    public SidgadClient(SidgadProperties props) {
        this.props = props;
        this.dataCache = Caffeine.newBuilder().expireAfterWrite(this.props.dataTtl()).maximumSize(500).build();
        this.catalogCache = Caffeine.newBuilder().expireAfterWrite(this.props.catalogTtl()).maximumSize(4).build();
        // Las actas pesan unos 130 KB: en memoria, las últimas usadas; el resto está en la base (SheetStore).
        this.sheetCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(800).build();
        this.profileCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(48)).maximumSize(10_000).build();
        this.logoCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(100).build();
        this.concurrency = new Semaphore(Math.max(1, this.props.maxConcurrency()), true);
        this.clubConcurrency = new Semaphore(Math.max(1, this.props.maxConcurrency() * clubFactor()), true);
    }

    private int clubFactor() {
        return Math.max(1, props.clubFactor());
    }

    /**
     * Corre el informe del club completo con su propio cupo de pedidos: {@code club-factor} veces los simultáneos y los
     * pedidos por segundo de los demás informes.
     */
    public void forClubReport(Runnable work) {
        CLUB_REPORT.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            CLUB_REPORT.remove();
        }
    }

    private static boolean clubReport() {
        return Boolean.TRUE.equals(CLUB_REPORT.get());
    }

    /**
     * Marca la sincronización (y el chequeo diario): en modo {@code stored} son los únicos que van a SIDGAD. Es
     * heredable, como {@link #CLUB_REPORT}: los hilos virtuales que abre la sincronización también la llevan.
     */
    private static final InheritableThreadLocal<Boolean> SYNC = new InheritableThreadLocal<>();

    /** Corre la sincronización (o el chequeo diario) con permiso para ir a SIDGAD. */
    public void syncing(Runnable work) {
        Boolean before = SYNC.get();
        SYNC.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            if (before == null) {
                SYNC.remove();
            } else {
                SYNC.set(before);
            }
        }
    }

    /** ¿Solo las copias guardadas? En modo {@code stored}, todo lo que no es la sincronización. */
    private boolean offline() {
        return readMode == ReadMode.STORED && !Boolean.TRUE.equals(SYNC.get());
    }

    private Semaphore slots() {
        return clubReport() ? clubConcurrency : concurrency;
    }

    /** Contenido que se devuelve cuando SIDGAD responde 404: el dato no está publicado. */
    public static final String NOT_PUBLISHED = "";

    /*
     * Todos los métodos devuelven NOT_PUBLISHED si el fragmento da 404 (SIDGAD no lo publicó): el informe sigue
     * con lo que haya y los parsers devuelven listas vacías. Un 404 no se cachea, para reintentarlo en el próximo
     * informe. Cualquier otro error HTTP o de red sí corta el informe.
     */

    /** Catálogo de competiciones ({@code cap_ls_1.php}), con sus clubes y equipos. Ver {@link #catalogCopy(String)}. */
    public String catalog() {
        return catalogCopy(CATALOG);
    }

    /** Página principal del portal (GET): trae la lista de temporadas con su año (selector "Temporada"). */
    public String portal() {
        return catalogCopy(PORTAL);
    }

    /** Claves de las copias del catálogo en {@link SnapshotStore}. */
    static final String CATALOG = "sidgad:ls";
    static final String PORTAL = "sidgad:portal";

    /** Lo que dejó una actualización del catálogo: si se pudo bajar todo y qué cambió respecto de la copia anterior. */
    public record CatalogRefresh(boolean downloaded, List<String> changes) {
    }

    /**
     * Baja de nuevo el catálogo y el portal aunque la copia esté vigente (la precarga de madrugada, una vez por día) y
     * guarda la copia nueva. Si SIDGAD falla, queda la anterior.
     */
    public CatalogRefresh refreshCatalog() {
        boolean downloaded = true;
        List<String> changes = List.of();
        for (String key : List.of(CATALOG, PORTAL)) {
            Optional<SnapshotStore.Stored> saved = stored(key);
            Optional<String> fresh;
            try {
                fresh = download(key);
            } catch (SidgadException e) {
                log.warn("SIDGAD falló al actualizar el catálogo ({}), queda la copia anterior: {}", key, e.getMessage());
                fresh = Optional.empty();
            }
            if (fresh.isEmpty()) {
                downloaded = false;
            } else if (key.equals(CATALOG) && saved.isPresent()) {
                changes = CatalogChanges.between(saved.get().body(), fresh.get());
            }
        }
        return new CatalogRefresh(downloaded, changes);
    }

    /** Una parte del catálogo, de la copia guardada si tiene menos de {@code catalog-ttl} (ver {@link #copy}). */
    private String catalogCopy(String key) {
        return copy(key, catalogCache, () -> fetchCatalog(key), this::recent);
    }

    /** Baja la parte del catálogo y la guarda (memoria y base); vacío si SIDGAD no la publicó. */
    private Optional<String> download(String key) {
        String body = fetchCatalog(key);
        if (body.equals(NOT_PUBLISHED)) {
            return Optional.empty();
        }
        catalogCache.put(key, body);
        save(key, body);
        return Optional.of(body);
    }

    private String fetchCatalog(String key) {
        return key.equals(PORTAL)
                ? fetch(props.origin() + "/", Connection.Method.GET, Map.of(), "portal")
                : post("cap_ls_1.php", Map.of("idm", "1"));
    }

    /**
     * De dónde leen los informes: {@code STORED}, solo las copias guardadas (a SIDGAD va únicamente la sincronización);
     * {@code SNAPSHOT}, las copias si están al día y si no SIDGAD; {@code LIVE}, SIDGAD (como antes).
     */
    enum ReadMode { STORED, SNAPSHOT, LIVE }

    /** Sin Spring (los tests de este cliente), las copias con SIDGAD de respaldo; la app usa {@code stored}. */
    private volatile ReadMode readMode = ReadMode.SNAPSHOT;
    private volatile boolean readSnapshots = true;

    /** {@code madsports.sidgad.read}: {@code stored} (por defecto), {@code snapshot} o {@code live}. */
    @Autowired
    public void setReadMode(@Value("${madsports.sidgad.read:stored}") String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        this.readMode = switch (m) {
            case "live" -> ReadMode.LIVE;
            case "snapshot" -> ReadMode.SNAPSHOT;
            default -> ReadMode.STORED;
        };
        this.readSnapshots = readMode != ReadMode.LIVE;
    }

    /** ¿La copia se bajó hace menos de {@code catalog-ttl}? La sincronización la renueva antes de que venza. */
    private boolean recent(Instant fetchedAt) {
        return fetchedAt.plus(props.catalogTtl()).isAfter(clock.instant());
    }

    /**
     * Un dato de SIDGAD: primero la memoria; después la copia guardada en la base, si sirve ({@code fresh}); si no, se
     * baja de SIDGAD y se guarda. Si SIDGAD falla o no lo publica y hay una copia guardada (aunque sea vieja), se sigue
     * con esa. En modo {@code live} no se leen las copias guardadas salvo para ese último caso.
     */
    private String copy(String key, Cache<String, String> memory, Supplier<String> source, Predicate<Instant> fresh) {
        String hit = memory.getIfPresent(key);
        if (hit != null) {
            return hit;
        }
        Optional<SnapshotStore.Stored> saved = stored(key);
        if (offline()) {
            // Solo lo guardado, aunque sea de días atrás: la sincronización lo renueva.
            String body = saved.map(SnapshotStore.Stored::body).orElseThrow(NotSyncedException::new);
            memory.put(key, body);
            return body;
        }
        if (readSnapshots && saved.isPresent() && fresh.test(saved.get().fetchedAt())) {
            memory.put(key, saved.get().body());
            return saved.get().body();
        }
        try {
            String body = source.get();
            if (!body.equals(NOT_PUBLISHED)) {
                memory.put(key, body);
                save(key, body);
                return body;
            }
        } catch (SidgadException e) {
            if (saved.isEmpty()) {
                throw e;
            }
            log.warn("SIDGAD falló al bajar {}, se sigue con la copia del {}: {}", key, saved.get().fetchedAt(),
                    e.getMessage());
        }
        if (saved.isEmpty()) {
            return NOT_PUBLISHED;
        }
        memory.put(key, saved.get().body());
        return saved.get().body();
    }

    /** Guarda la copia en la base; true si es nueva o cambió. Si no se puede guardar, queda solo en memoria. */
    private boolean save(String key, String body) {
        SnapshotStore store = snapshotStore;
        if (store == null) {
            return false;
        }
        try {
            return store.put(key, body);
        } catch (RuntimeException e) {
            log.warn("No se pudo guardar la copia {}: {}", key, e.getMessage());
            return false;
        }
    }

    private Optional<SnapshotStore.Stored> stored(String key) {
        SnapshotStore store = snapshotStore;
        if (store == null) {
            return Optional.empty();
        }
        try {
            return store.get(key);
        } catch (RuntimeException e) {
            log.warn("No se pudo leer la copia {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** Los datos de una competición que se copian: tabla, calendario y estadísticas por jugador. */
    public enum Fragment {
        STANDINGS("standings"), CALENDAR("calendar"), STATS("stats");

        private final String kind;

        Fragment(String kind) {
            this.kind = kind;
        }

        /** Clave de la copia: {@code sidgad:calendar:779}. */
        public String key(int idc) {
            return "sidgad:" + kind + ":" + idc;
        }

        String file(int idc) {
            return switch (this) {
                case STANDINGS -> "cap_clasif_idc_" + idc + "_1.php";
                case CALENDAR -> "cap_cal_idc_" + idc + "_1.php";
                case STATS -> "cap_stats_1_" + idc + ".php";
            };
        }

        /** Las 13 columnas de las estadísticas vienen siempre; {@code tipo_stats} solo cambia el orden. */
        Map<String, String> params(int idc) {
            return this == STATS
                    ? Map.of("idc", String.valueOf(idc), "tipo_stats", "goles", "site_lang", "es")
                    : Map.of("idc", String.valueOf(idc));
        }
    }

    /** Cuándo se bajó la copia guardada de ese dato de la competición; vacío si no hay copia. */
    public Optional<Instant> fetchedAt(Fragment f, int idc) {
        return stored(f.key(idc)).map(SnapshotStore.Stored::fetchedAt);
    }

    /**
     * La fecha de los datos de un informe: la más vieja de las copias de tabla, calendario y estadísticas de esas
     * competiciones. Vacío si no hay copias guardadas (sin base, o leyendo de SIDGAD en el momento).
     */
    public Optional<Instant> oldestCopy(Collection<Integer> competitions) {
        return competitions.stream()
                .flatMap(idc -> Arrays.stream(Fragment.values()).map(f -> fetchedAt(f, idc)))
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder());
    }

    /** La tabla de posiciones de la competición. */
    public String standings(int idc) {
        return fragment(Fragment.STANDINGS, idc);
    }

    /** El calendario con los resultados. */
    public String calendar(int idc) {
        return fragment(Fragment.CALENDAR, idc);
    }

    /** Estadísticas por jugador. */
    public String playerStats(int idc) {
        return fragment(Fragment.STATS, idc);
    }

    private String fragment(Fragment f, int idc) {
        return copy(f.key(idc), dataCache, () -> post(f.file(idc), f.params(idc)), this::recent);
    }

    /**
     * Baja de nuevo un dato de la competición aunque la copia esté vigente (la sincronización programada) y lo guarda.
     * Si SIDGAD no lo publicó, queda la copia anterior.
     *
     * @return true si es nuevo o cambió respecto de la copia guardada
     */
    public boolean refresh(Fragment f, int idc) {
        String body = post(f.file(idc), f.params(idc));
        if (body.equals(NOT_PUBLISHED)) {
            // Sin copia anterior se guarda vacío: "SIDGAD no lo publicó", y los informes no lo dan por faltante.
            if (stored(f.key(idc)).isEmpty()) {
                save(f.key(idc), NOT_PUBLISHED);
            }
            return false;
        }
        dataCache.put(f.key(idc), body);
        return save(f.key(idc), body);
    }

    /** Las actas se guardan de forma permanente en este almacén (lo registra la app; sin él, solo en memoria). */
    @Autowired(required = false)
    public void setSheetStore(SheetStore store) {
        this.sheetStore = store;
    }

    /**
     * Las copias de SIDGAD (catálogo, tablas, calendarios, estadísticas, fichas) se guardan en este almacén (lo registra
     * la app; sin él, solo en memoria).
     */
    @Autowired(required = false)
    public void setSnapshotStore(SnapshotStore store) {
        this.snapshotStore = store;
    }

    /** Reloj con que se mide la vigencia de las copias guardadas (los tests lo fijan). */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Acta de un partido. Solo se piden actas de partidos ya jugados, que no cambian: primero la memoria (24 h), después
     * las guardadas ({@link SheetStore}) y, si no está, SIDGAD (y se guarda).
     */
    public String gameSheet(int idc, int idp) {
        String hit = sheetCache.getIfPresent(idp);
        if (hit != null) {
            return hit;
        }
        SheetStore store = sheetStore;
        Optional<String> saved = Optional.empty();
        if (store != null) {
            try {
                saved = store.get(idp);
            } catch (RuntimeException e) {
                log.warn("No se pudo leer el acta guardada {}: {}", idp, e.getMessage());
            }
        }
        if (saved.isPresent()) {
            sheetCache.put(idp, saved.get());
            return saved.get();
        }
        if (offline()) {
            // La sincronización baja las actas de todos los partidos jugados; la que falta, para el informe, no está.
            return NOT_PUBLISHED;
        }
        String body = post("cap_gr_" + idp + "_1.php", Map.of(
                "idm", "1", "idc", String.valueOf(idc), "idp", String.valueOf(idp), "tab", "tab_ficha_resumen"));
        if (!body.equals(NOT_PUBLISHED)) {
            sheetCache.put(idp, body);
            if (store != null) {
                try {
                    store.put(idp, body);
                } catch (RuntimeException e) {
                    log.warn("No se pudo guardar el acta {}: {}", idp, e.getMessage());
                }
            }
        }
        return body;
    }

    /**
     * Ficha de temporada de un jugador ({@code seasonId} = id_temp): sus partidos de cada competición, incluidos los
     * que no tienen acta publicada, con goles, libres, penales, azules y rojas.
     */
    public String playerSeason(int playerId, int seasonId) {
        // De las copias guardadas sirve la que la sincronización dejó al día; leyendo de SIDGAD, la de los últimos minutos.
        Duration age = readSnapshots ? props.catalogTtl() : props.dataTtl();
        return playerSeason(playerId, seasonId, clock.instant().minus(age));
    }

    /** Clave de la copia de la ficha de temporada: {@code sidgad:profile:<jugador>:<temporada>}. */
    static String profileKey(int playerId, int seasonId) {
        return "sidgad:profile:" + playerId + ":" + seasonId;
    }

    /**
     * La ficha de temporada, si la que hay (en memoria o guardada en la base) se bajó desde {@code notBefore}; si no, de
     * nuevo de SIDGAD, y se guarda. Cambia con cada partido: el informe del club la acepta si se bajó después del día de
     * su último partido (la sincronización de la madrugada las deja al día). Si SIDGAD falla, sirve la guardada.
     */
    public String playerSeason(int playerId, int seasonId, Instant notBefore) {
        String file = "profiles/cap_profileseason_" + playerId + "_1_" + seasonId + ".php";
        Fetched hit = profileCache.getIfPresent(file);
        if (hit != null && !hit.at().isBefore(notBefore)) {
            return hit.body();
        }
        String key = profileKey(playerId, seasonId);
        Optional<SnapshotStore.Stored> saved = stored(key);
        if (offline()) {
            // La que dejó la sincronización (las de quienes jugaron, al día); sin ficha guardada, no hay partidos.
            saved.ifPresent(s -> profileCache.put(file, new Fetched(s.body(), s.fetchedAt())));
            return saved.map(SnapshotStore.Stored::body).orElse(NOT_PUBLISHED);
        }
        if (readSnapshots && saved.isPresent() && !saved.get().fetchedAt().isBefore(notBefore)) {
            profileCache.put(file, new Fetched(saved.get().body(), saved.get().fetchedAt()));
            return saved.get().body();
        }
        Instant at = clock.instant();
        String body;
        try {
            body = post(file, Map.of("idm", "1", "id_player", String.valueOf(playerId)));
        } catch (SidgadException e) {
            if (saved.isEmpty()) {
                throw e;
            }
            log.warn("SIDGAD falló al bajar la ficha {}, se sigue con la del {}: {}", key, saved.get().fetchedAt(),
                    e.getMessage());
            return saved.get().body();
        }
        if (!body.equals(NOT_PUBLISHED)) {
            profileCache.put(file, new Fetched(body, at));
            save(key, body);
        }
        return body;
    }

    /** Lo bajado de SIDGAD y cuándo. */
    private record Fetched(String body, Instant at) {
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
        Optional<byte[]> saved = stored(logoKey(file)).map(s -> java.util.Base64.getDecoder().decode(s.body()));
        if (saved.isPresent()) {
            logoCache.put(file, saved.get());
            return saved.get();
        }
        if (offline()) {
            return null;
        }
        Semaphore slots = slots();
        try {
            slots.acquire();
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
            save(logoKey(file), java.util.Base64.getEncoder().encodeToString(body));
            return body;
        } catch (IOException | RuntimeException e) {
            log.info("No se pudo bajar el escudo {}: {}", file, e.getMessage());
            return null;
        } finally {
            slots.release();
        }
    }

    /** Clave de la copia de un escudo (en Base64): {@code sidgad:logo:22_10.png}. */
    static String logoKey(String file) {
        return "sidgad:logo:" + file;
    }

    /** URL base de los escudos (los tests la reemplazan por un servidor local). */
    protected String logosUrl() {
        return LOGOS_URL;
    }

    private String post(String file, Map<String, String> params) {
        return fetch(props.baseUrl() + file, Connection.Method.POST, params, file);
    }

    private String fetch(String url, Connection.Method method, Map<String, String> params, String file) {
        Semaphore slots = slots();
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SidgadException("Interrumpido esperando turno", e);
        }
        try {
            return doFetch(url, method, params, file);
        } finally {
            slots.release();
        }
    }

    /** Las esperas ante un 429 (los tests las acortan). */
    void retryBackoff(List<Duration> backoff) {
        this.backoff = List.copyOf(backoff);
    }

    /**
     * Un pedido a SIDGAD. Si pide bajar el ritmo (responde 429, o la conexión HTTP/2 rechaza el pedido por "too many
     * concurrent streams"), todos los pedidos se pausan un rato y este se reintenta (ver {@link #backoff}).
     */
    private String doFetch(String url, Connection.Method method, Map<String, String> params, String file) {
        for (int attempt = 0; ; attempt++) {
            Connection.Response res;
            try {
                res = request(url, method, params, file);
            } catch (SidgadException e) {
                if (!tooManyStreams(e) || attempt >= backoff.size()) {
                    throw e;
                }
                slowDown(file, "demasiados pedidos simultáneos en la conexión", backoff.get(attempt), attempt);
                continue;
            }
            if (res.statusCode() == 429 && attempt < backoff.size()) {
                slowDown(file, "429", retryAfter(res.header("Retry-After")).orElse(backoff.get(attempt)), attempt);
                continue;
            }
            return body(res, file);
        }
    }

    private void slowDown(String file, String why, Duration pause, int attempt) {
        log.warn("SIDGAD pidió bajar el ritmo ({}) en {}: se pausan los pedidos {} s (reintento {} de {})",
                why, file, pause.toSeconds(), attempt + 1, backoff.size());
        pause(pause);
    }

    /**
     * ¿El pedido falló porque la conexión HTTP/2 con SIDGAD ya tenía todos los pedidos simultáneos que acepta? El
     * cliente HTTP de Java no los encola: avisa con una IOException "too many concurrent streams".
     */
    static boolean tooManyStreams(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().toLowerCase(Locale.ROOT)
                    .contains("too many concurrent streams")) {
                return true;
            }
        }
        return false;
    }

    /** "30" (segundos) del encabezado Retry-After, hasta {@link #MAX_PAUSE}; vacío si no viene o es una fecha. */
    static Optional<Duration> retryAfter(String header) {
        if (header == null || !header.trim().matches("\\d{1,6}")) {
            return Optional.empty();
        }
        Duration d = Duration.ofSeconds(Long.parseLong(header.trim()));
        return Optional.of(d.compareTo(MAX_PAUSE) > 0 ? MAX_PAUSE : d);
    }

    /** Ningún pedido sale antes de que pase la pausa (si ya había una más larga, queda esa). */
    private void pause(Duration pause) {
        long until = System.nanoTime() + pause.toNanos();
        pausedUntil.accumulateAndGet(until, (current, next) -> next - current > 0 ? next : current);
    }

    private String body(Connection.Response res, String file) {
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
    }

    /** Un pedido, sin reintentos; un error de red es una {@link SidgadException} (los tests lo reemplazan). */
    Connection.Response request(String url, Connection.Method method, Map<String, String> params, String file) {
        waitTurn();
        try {
            return Jsoup.connect(url)
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
        } catch (IOException e) {
            throw new SidgadException("No se pudo consultar " + file + ": " + e.getMessage(), e);
        }
    }

    private void waitTurn() {
        throttle.lock();
        try {
            long interval = props.minInterval().toNanos() / (clubReport() ? clubFactor() : 1);
            long now = System.nanoTime();
            long wait = Math.max(interval - (now - lastRequestNanos), pausedUntil.get() - now);
            if (wait > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(wait));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SidgadException("Interrumpido esperando turno", e);
                }
            }
            lastRequestNanos = System.nanoTime();
        } finally {
            throttle.unlock();
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

    /** Lo pedido todavía no está guardado (modo {@code stored}): lo baja la próxima sincronización. */
    public static class NotSyncedException extends SidgadException {
        public static final String MESSAGE = "Estos datos de SIDGAD todavía no están guardados: se bajan en la "
                + "sincronización (al arrancar, a las 5:00 y en días de partido). Probá de nuevo en unos minutos.";

        public NotSyncedException() {
            super(MESSAGE);
        }
    }
}
