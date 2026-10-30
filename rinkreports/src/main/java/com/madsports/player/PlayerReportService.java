package com.madsports.player;

import com.madsports.catalog.ClubTeams;
import com.madsports.catalog.RosterService;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.report.CategoryExtractor;
import com.madsports.report.Crests;
import com.madsports.report.ExcelExporter;
import com.madsports.report.PdfRenderer;
import com.madsports.report.ReportService.Scope;
import com.madsports.report.ReportService.Status;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.SeasonMatch;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.source.GameSheet;
import com.madsports.source.HockeySource;
import com.madsports.source.HockeySources;
import com.madsports.source.SourceException;
import com.madsports.stats.ReportData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Informe de un jugador (perfil PLAYERFPP): en qué categorías de su club jugó en el año y período, y por categoría
 * y en total los partidos (ganados, empatados, perdidos), goles y tarjetas, con sus promedios por partido.
 * <p>
 * Se leen las actas de los partidos jugados por todos los equipos del club en el período y se busca al jugador por su
 * {@code id_player} en la planilla de su equipo (la tabla de estadísticas de la FPP solo lista a los goleadores, así
 * que no alcanza para saber dónde jugó). Las actas quedan en cache 24 h y las comparte el informe de club.
 */
@Service
public class PlayerReportService {

    private static final Logger log = LoggerFactory.getLogger(PlayerReportService.class);

    /**
     * @param playerName nombre del jugador en la FPP
     * @param fullName   nombre y apellido del usuario (como se registró)
     * @param scope      Apertura, Clausura o Apertura + Clausura
     * @param levels     categorías a revisar (ver {@link Eligibility}); vacía = todas
     * @param branch     rama del jugador (MASCULINO o FEMENINO); null = ambas
     * @param base       su categoría real, ya determinada (la más baja en que jugó); null si todavía no se sabe
     * @param federacion federación del jugador (la de su perfil: FPP o AMP)
     */
    public record Query(int playerId, String playerName, String fullName, int clubId, int year, Scope scope,
                        List<String> levels, String branch, String base, Federation federation) {

        public Query(int playerId, String playerName, String fullName, int clubId, int year, Scope scope) {
            this(playerId, playerName, fullName, clubId, year, scope, List.of(), null, null, Federation.FPP);
        }

        public Query(int playerId, String playerName, String fullName, int clubId, int year, Scope scope,
                     List<String> levels, String branch) {
            this(playerId, playerName, fullName, clubId, year, scope, levels, branch, null, Federation.FPP);
        }

        public Query(int playerId, String playerName, String fullName, int clubId, int year, Scope scope,
                     List<String> levels, String branch, Federation federation) {
            this(playerId, playerName, fullName, clubId, year, scope, levels, branch, null, federation);
        }

        /** Con su categoría real: se revisan solo las categorías en que puede jugar un jugador de esa categoría. */
        Query withBase(String level) {
            return new Query(playerId, playerName, fullName, clubId, year, scope,
                    Eligibility.levels(federation, level), branch, level, federation);
        }

        boolean eligible(Competition c) {
            return (levels.isEmpty() || levels.contains(c.category().level()))
                    && (branch == null || branch.isBlank() || branch.equals(c.category().branch()));
        }

        /** "Primera y Reserva Masculino" (las categorías revisadas), o null si se revisan todas. */
        String eligibleLabel() {
            if (levels.isEmpty()) {
                return null;
            }
            List<String> names = levels.stream()
                    .map(l -> new Competition.Category(l, branch == null ? "" : branch).levelLabel()).toList();
            String joined = names.size() == 1 ? names.getFirst()
                    : String.join(", ", names.subList(0, names.size() - 1)) + " y " + names.getLast();
            return joined + (branch == null || branch.isBlank() ? "" : " " + new Competition.Category("", branch).branchLabel());
        }
    }

    public static final class Job implements com.madsports.report.ReadyReport {
        private final UUID id = UUID.randomUUID();
        private final String owner;
        private volatile Status status = Status.PENDING;
        private volatile String message = "En cola";
        private volatile byte[] pdf;
        private volatile String html;
        private volatile byte[] xlsx;
        private volatile String fileName;

        Job(String owner) {
            this.owner = owner;
        }

        public UUID id() { return id; }
        public Status status() { return status; }
        public String message() { return message; }
        public byte[] pdf() { return pdf; }
        /** El informe para ver en pantalla: el mismo HTML que el PDF. */
        public String html() { return html; }
        public byte[] xlsx() { return xlsx; }
        public String fileName() { return fileName; }

        /** El nombre del archivo con otra extensión ("xlsx"). */
        public String fileName(String extension) { return fileName.substring(0, fileName.lastIndexOf('.') + 1) + extension; }

        /** Lo que corre al quedar listo (guardarlo en el historial), antes de que se vea como listo. */
        private final java.util.List<Runnable> onReady = new java.util.ArrayList<>();
        /**
         * Cuida onReady. Un lock (no synchronized): lo que corre guarda en la base y, en Java 21, esperar en un
         * synchronized fija el hilo virtual a su núcleo.
         */
        private final ReentrantLock readyLock = new ReentrantLock();

        @Override
        public void whenReady(Runnable action) {
            readyLock.lock();
            try {
                if (status != Status.READY) {
                    onReady.add(action);
                    return;
                }
            } finally {
                readyLock.unlock();
            }
            action.run();
        }

        void update(Status status, String message) {
            if (status == Status.READY) {
                readyLock.lock();
                try {
                    onReady.forEach(com.madsports.report.ReadyReport::runQuietly);
                    onReady.clear();
                    this.message = message;
                    this.status = status;
                } finally {
                    readyLock.unlock();
                }
                return;
            }
            this.status = status;
            this.message = message;
        }
    }

    private final CatalogService catalog;
    private final HockeySources sources;
    private final PdfRenderer renderer;
    private final ExcelExporter excel;
    private final Clock clock;
    private final Cache<UUID, Job> jobs = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public PlayerReportService(CatalogService catalog, HockeySources sources, PdfRenderer renderer, ExcelExporter excel,
                               Clock clock) {
        this.catalog = catalog;
        this.sources = sources;
        this.renderer = renderer;
        this.excel = excel;
        this.clock = clock;
    }

    public Job submit(Query query, String owner) {
        if (query.scope() == Scope.LAST_5) {
            throw new IllegalArgumentException("Para jugadores el período es Apertura, Clausura o Apertura + Clausura.");
        }
        if (!catalog.available(query.federation(), query.year())) {
            throw new IllegalArgumentException(CatalogService.unavailableMessage(query.federation(), query.year()));
        }
        Job job = new Job(owner);
        jobs.put(job.id(), job);
        executor.submit(() -> run(job, query));
        return job;
    }

    /** Solo el dueño ve su informe. */
    public Optional<Job> find(UUID id, String owner) {
        return Optional.ofNullable(jobs.getIfPresent(id)).filter(j -> Objects.equals(j.owner, owner));
    }

    private void run(Job job, Query q) {
        try {
            PlayerReport report = build(q, job);
            job.update(Status.RENDERING, "Generando el PDF");
            job.pdf = renderer.playerPdf(report);
            job.html = renderer.playerHtml(report);
            job.xlsx = excel.player(report);
            job.fileName = "informe-jugador-" + slug(q.playerName()) + "-" + q.year() + "-"
                    + q.scope().name().toLowerCase() + ".pdf";
            job.update(Status.READY, "Informe listo");
        } catch (RuntimeException e) {
            log.error("Falló el informe de jugador {}", job.id(), e);
            job.update(Status.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    PlayerReport build(Query q, Job job) {
        ClubView club = catalog.club(q.federation(), q.clubId(), q.year());
        HockeySource source = sources.of(q.federation());
        List<Competition> all = catalog.competitions(q.federation(), q.year()).stream()
                .filter(c -> !ClubTeams.own(c, club).isEmpty())
                .toList();
        // Las letras A/B se deciden con todo el año (como en el informe de club); después se filtra el período.
        Map<Integer, String> keys = ClubTeams.keys(all, club);
        // Solo las categorías en que puede jugar (puede subir de categoría, nunca bajar) y su rama.
        List<Competition> comps = all.stream()
                .filter(c -> q.scope().tournament() == null || c.tournament() == q.scope().tournament())
                .filter(q::eligible)
                .toList();
        List<TeamInput> inputs = new ArrayList<>();
        Map<Competition, Future<List<PlayerStats>>> scorerTables = new LinkedHashMap<>();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            job.update(Status.EXTRACTING, "Leyendo los calendarios de los equipos del club");
            Map<Competition, Future<List<Match>>> calendars = new LinkedHashMap<>();
            comps.forEach(c -> calendars.put(c, io.submit(() -> source.calendar(c).orElse(List.of()))));
            // La tabla de goleadores de cada torneo, para su lugar en la liga.
            comps.forEach(c -> scorerTables.put(c, io.submit(() -> source.playerStats(c).orElse(List.of()))));
            Map<Competition, List<Match>> calendar = new LinkedHashMap<>();
            for (Competition c : comps) {
                calendar.put(c, ClubTeams.await(calendars.get(c)));
            }

            Map<Integer, Competition> toRead = new LinkedHashMap<>();
            for (Competition c : comps) {
                for (TeamRef t : ClubTeams.own(c, club)) {
                    PlayerReportAssembler.teamMatches(calendar.get(c), t.teamId()).forEach(m -> toRead.putIfAbsent(m.idp(), c));
                }
            }
            AtomicInteger done = new AtomicInteger();
            job.update(Status.EXTRACTING, "Leyendo actas de partido (0/" + toRead.size() + ")");
            Map<Integer, Future<GameSheet>> sheets = new LinkedHashMap<>();
            toRead.forEach((idp, c) -> sheets.put(idp, io.submit(() -> {
                GameSheet sheet = source.gameSheet(c, idp);
                job.update(Status.EXTRACTING, "Leyendo actas de partido (" + done.incrementAndGet() + "/" + toRead.size() + ")");
                return sheet;
            })));

            for (Competition c : comps) {
                for (TeamRef t : ClubTeams.own(c, club)) {
                    List<Match> matches = PlayerReportAssembler.teamMatches(calendar.get(c), t.teamId());
                    Map<Integer, List<LineupPlayer>> lineups = new LinkedHashMap<>();
                    Map<Integer, List<MatchEvent>> events = new LinkedHashMap<>();
                    for (Match m : matches) {
                        GameSheet sheet = ClubTeams.await(sheets.get(m.idp()));
                        lineups.put(m.idp(), RosterService.ownSide(sheet.lineups(), m, Set.of(t.teamId())));
                        events.put(m.idp(), sheet.events());
                    }
                    inputs.add(new TeamInput(c, ClubTeams.label(c, club, keys.getOrDefault(t.teamId(), club.code())),
                            t.teamId(), matches, lineups, events));
                }
            }
        }
        inputs = withSummaries(source, q, inputs, job);
        ReportData.ClubInfo info = new ReportData.ClubInfo(club.id(), club.code(), club.name(),
                Crests.tile(source.clubLogo(CategoryExtractor.logoFile(all, club.id()))));
        // Su categoría real es la más baja en que jugó; el informe se queda con las categorías que le corresponden.
        Query resolved = PlayerReportAssembler.baseLevel(q.federation(), q.playerId(), inputs).map(q::withBase).orElse(q);
        List<TeamInput> own = inputs.stream().filter(in -> resolved.eligible(in.competition())).toList();
        return PlayerReportAssembler.assemble(resolved, info, LocalDate.now(clock), q.federation().levels(), own)
                .withLeagueRanks(leagueRanks(q.playerId(), own, scorerTables));
    }

    /**
     * Su lugar en la tabla de goleadores de cada categoría: suma los goles de cada jugador en los torneos de esa
     * categoría (Apertura y Clausura) y lo ubica entre todos. Sin goles, o si una tabla no se pudo leer, esa categoría
     * queda sin lugar en la liga.
     */
    static Map<String, PlayerReport.LeagueRank> leagueRanks(int playerId, List<TeamInput> inputs,
                                                            Map<Competition, Future<List<PlayerStats>>> scorerTables) {
        Map<String, Set<Competition>> byCategory = new LinkedHashMap<>();
        inputs.forEach(in -> byCategory.computeIfAbsent(in.category(), k -> new LinkedHashSet<>()).add(in.competition()));
        Map<String, PlayerReport.LeagueRank> ranks = new LinkedHashMap<>();
        byCategory.forEach((category, comps) -> {
            List<List<PlayerStats>> tables = new ArrayList<>();
            for (Competition c : comps) {
                Future<List<PlayerStats>> f = scorerTables.get(c);
                try {
                    tables.add(f == null ? List.of() : ClubTeams.await(f));
                } catch (RuntimeException e) {
                    log.info("No se pudo leer la tabla de goleadores de {}: {}", c.id(), e.getMessage());
                    return;
                }
            }
            PlayerReport.LeagueRank rank = PlayerReportAssembler.leagueRank(playerId, tables);
            if (rank != null) {
                ranks.put(category, rank);
            }
        });
        return ranks;
    }

    /**
     * Partidos sin acta publicada: SIDGAD los lista igual en la ficha de temporada del jugador, con sus goles, libres,
     * penales, azules y rojas; gestiondepsj, los partidos en que tuvo algún evento (sin penales ni libres). Se pide la
     * ficha de las competiciones con partidos sin acta. Si no se puede leer, esos partidos quedan como "sin acta".
     */
    private List<TeamInput> withSummaries(HockeySource source, Query q, List<TeamInput> inputs, Job job) {
        List<Competition> missing = inputs.stream().filter(in -> in.matches().stream()
                        .anyMatch(m -> in.sheets().getOrDefault(m.idp(), List.of()).isEmpty()))
                .map(TeamInput::competition).distinct().toList();
        if (missing.isEmpty()) {
            return inputs;
        }
        job.update(Status.EXTRACTING, "Leyendo la ficha de temporada del jugador (partidos sin acta)");
        List<SeasonMatch> season;
        try {
            season = source.playerSeason(q.playerId(), missing);
        } catch (SourceException e) {
            log.info("No se pudo leer la ficha de temporada del jugador {}: {}", q.playerId(), e.getMessage());
            return inputs;
        }
        if (season.isEmpty()) {
            return inputs;
        }
        return inputs.stream().map(in -> in.withSummaries(PlayerReportAssembler.summaries(in, season),
                source.kind().fullSeason())).toList();
    }


    static String slug(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}