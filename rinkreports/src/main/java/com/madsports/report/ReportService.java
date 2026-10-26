package com.madsports.report;

import com.madsports.analysis.AnalysisResult;
import com.madsports.analysis.GeminiAnalyst;
import com.madsports.analysis.RuleBasedAnalyst;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.CategoryOption;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Competition.Tournament;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.SeasonMatch;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.sidgad.parse.SidgadParser;
import com.madsports.stats.ReportData;
import com.madsports.stats.StatsEngine;
import com.madsports.stats.StatsEngine.CompetitionData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Orquesta un informe: extracción (SIDGAD) → cálculo (StatsEngine) → análisis (Gemini o reglas) → PDF.
 * Corre en segundo plano; el cliente consulta el estado del job y descarga el PDF al terminar.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    public enum Status { PENDING, EXTRACTING, ANALYZING, RENDERING, READY, ERROR }

    /** Período que cubre el informe. */
    public enum Scope {
        OPENING(Tournament.OPENING, 0, "Apertura"),
        CLOSING(Tournament.CLOSING, 0, "Clausura"),
        SEASON(null, 0, "Apertura + Clausura"),
        LAST_5(null, 5, "Últimos 5 partidos");

        private final Tournament tournament;
        private final int lastMatches;
        private final String label;

        Scope(Tournament tournament, int lastMatches, String label) {
            this.tournament = tournament;
            this.lastMatches = lastMatches;
            this.label = label;
        }

        public Tournament tournament() { return tournament; }
        public int lastMatches() { return lastMatches; }
        public String label() { return label; }

        public static Scope parse(String value) {
            if (value == null || value.isBlank()) {
                return SEASON;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Período inválido: " + value + ". Válidos: "
                        + Arrays.toString(values()));
            }
        }
    }

    /**
     * Parámetros del informe: club, categoría (nivel), rama y período.
     *
     * @param year       año (temporada) ya validado contra los ofrecidos
     * @param federacion federación del club (la del perfil del usuario)
     */
    public record Request(int clubId, String category, String branch, Scope scope, int year, Federation federation) {

        public Request(int clubId, String category, String branch, Scope scope, int year) {
            this(clubId, category, branch, scope, year, Federation.FPP);
        }
    }

    public static final class Job implements ReadyReport {
        private final UUID id = UUID.randomUUID();
        private final Request request;
        private final String owner;
        private volatile Status status = Status.PENDING;
        private volatile String message = "En cola";
        private volatile byte[] pdf;
        private volatile String html;
        private volatile byte[] xlsx;
        private volatile String fileName;

        Job(Request request, String owner) {
            this.request = request;
            this.owner = owner;
        }

        public UUID id() { return id; }
        public Request request() { return request; }
        public Status status() { return status; }
        public String message() { return message; }
        public byte[] pdf() { return pdf; }
        /** El informe para ver en pantalla: el mismo HTML que el PDF. */
        public String html() { return html; }
        public byte[] xlsx() { return xlsx; }
        /** "informe-club-cavs-temporada-2026.pdf" */
        public String fileName() { return fileName; }

        /** El nombre del archivo con otra extensión ("xlsx"). */
        public String fileName(String extension) { return fileName.substring(0, fileName.lastIndexOf('.') + 1) + extension; }

        /** Lo que corre al quedar listo (guardarlo en el historial), antes de que se vea como listo. */
        private final java.util.List<Runnable> onReady = new java.util.ArrayList<>();

        @Override
        public void whenReady(Runnable action) {
            synchronized (onReady) {
                if (status != Status.READY) {
                    onReady.add(action);
                    return;
                }
            }
            action.run();
        }

        void update(Status status, String message) {
            if (status == Status.READY) {
                synchronized (onReady) {
                    onReady.forEach(ReadyReport::runQuietly);
                    onReady.clear();
                    this.message = message;
                    this.status = status;
                }
                return;
            }
            this.status = status;
            this.message = message;
        }
    }

    private final CatalogService catalog;
    private final SidgadClient sidgad;
    private final GeminiAnalyst gemini;
    private final RuleBasedAnalyst rules;
    private final PdfRenderer renderer;
    private final ExcelExporter excel;
    private final Clock clock;
    private final Cache<UUID, Job> jobs = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    /** Análisis de Gemini a la vez en el informe del club (el plan gratuito limita los pedidos por minuto). */
    static final int CLUB_ANALYSES = 3;

    public ReportService(CatalogService catalog, SidgadClient sidgad, GeminiAnalyst gemini,
                         RuleBasedAnalyst rules, PdfRenderer renderer, ExcelExporter excel, Clock clock) {
        this.catalog = catalog;
        this.sidgad = sidgad;
        this.gemini = gemini;
        this.rules = rules;
        this.renderer = renderer;
        this.excel = excel;
        this.clock = clock;
    }

    /** Valida los parámetros (falla rápido) y encola el informe. */
    /** @param owner usuario que pide el informe: solo él puede consultarlo y bajarlo */
    public Job submit(Request request, String owner) {
        Federation fed = request.federation();
        if (!catalog.available(fed, request.year())) {
            throw new IllegalArgumentException(CatalogService.unavailableMessage(fed, request.year()));
        }
        ClubView club = catalog.club(fed, request.clubId(), request.year());
        CategoryOption option = CategoryOption.parse(request.category());
        Category category = catalog.category(fed, option.level(), request.branch());
        // A y B son equipos distintos: la categoría elegida tiene que ser una de las opciones del club en esa rama.
        boolean exists = club.categories().stream()
                .anyMatch(l -> l.key().equals(option.key()) && l.branches().contains(category.branch()));
        if (!exists) {
            List<String> valid = club.categories().stream()
                    .filter(l -> CategoryOption.parse(l.key()).level().equals(option.level())
                            && l.branches().contains(category.branch()))
                    .map(CatalogService.LevelView::label).toList();
            throw new IllegalArgumentException(club.name() + " no tiene equipo en " + label(category, option)
                    + " en " + request.year() + "." + (valid.isEmpty() ? "" : " Opciones: " + String.join(", ", valid) + "."));
        }
        Tournament tournament = request.scope().tournament();
        if (catalog.competitionsFor(fed, club.id(), category, tournament, request.year()).isEmpty()) {
            throw new IllegalArgumentException(club.name() + " no participa en " + label(category, option)
                    + (tournament == null ? "" : " - " + tournament.label()) + " en " + request.year() + ".");
        }
        Job job = new Job(request, owner);
        jobs.put(job.id(), job);
        executor.submit(() -> run(job, club, category, option));
        return job;
    }

    /** Una categoría del club (un equipo) en una rama, para el informe del club completo. */
    record ClubCategory(Category category, CategoryOption option, String levelLabel) {

        /** Cómo se elige en la web: "JUVENIL|MASCULINO", "INFANTIL:B|FEMENINO". */
        String selectionKey() {
            return option.key() + "|" + category.branch();
        }
    }

    /**
     * Informe del club completo: todas sus categorías, primero las masculinas y después las femeninas, en el período.
     * Valida (falla rápido) y encola; cada categoría se arma como su informe y lleva su análisis.
     */
    public Job submitClub(int clubId, Scope scope, int year, Federation fed, String owner) {
        return submitClub(clubId, scope, year, fed, owner, null);
    }

    /**
     * El informe del club con solo las categorías elegidas, de una o de las dos ramas.
     *
     * @param selection "JUVENIL|MASCULINO", "INFANTIL:B|FEMENINO"... (categoría como en el catálogo y rama); vacío o
     *                  null, todas las del club
     */
    public Job submitClub(int clubId, Scope scope, int year, Federation fed, String owner, List<String> selection) {
        if (!catalog.available(fed, year)) {
            throw new IllegalArgumentException(CatalogService.unavailableMessage(fed, year));
        }
        ClubView club = catalog.club(fed, clubId, year);
        List<ClubCategory> all = clubCategories(club, fed, scope, year);
        if (all.isEmpty()) {
            throw new IllegalArgumentException(club.name() + " no tiene equipos"
                    + (scope.tournament() == null ? "" : " en el " + scope.tournament().label()) + " en " + year + ".");
        }
        List<ClubCategory> categories = selection == null || selection.isEmpty() ? all : select(all, selection);
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("Ninguna de las categorías elegidas tiene partidos"
                    + (scope.tournament() == null ? "" : " en el " + scope.tournament().label()) + " en " + year
                    + ". Elegí otras.");
        }
        Job job = new Job(new Request(clubId, null, null, scope, year, fed), owner);
        jobs.put(job.id(), job);
        executor.submit(() -> sidgad.forClubReport(() -> runClub(job, club, categories)));
        return job;
    }

    /** De las categorías del club, las elegidas ("JUVENIL|MASCULINO", sin distinguir mayúsculas), en el mismo orden. */
    static List<ClubCategory> select(List<ClubCategory> categories, List<String> selection) {
        java.util.Set<String> chosen = selection.stream().filter(java.util.Objects::nonNull)
                .map(k -> k.trim().toUpperCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        return categories.stream().filter(c -> chosen.contains(c.selectionKey())).toList();
    }

    /** Las categorías del club que juegan en el período: Masculino y después Femenino, cada una de Infantil a Primera. */
    List<ClubCategory> clubCategories(ClubView club, Federation fed, Scope scope, int year) {
        List<ClubCategory> out = new ArrayList<>();
        for (String branch : List.of("MASCULINO", "FEMENINO")) {
            for (CatalogService.LevelView l : club.categories()) {
                if (!l.branches().contains(branch)) {
                    continue;
                }
                CategoryOption option = CategoryOption.parse(l.key());
                Category category = catalog.category(fed, option.level(), branch);
                if (!catalog.competitionsFor(fed, club.id(), category, scope.tournament(), year).isEmpty()) {
                    out.add(new ClubCategory(category, option, l.label()));
                }
            }
        }
        return out;
    }

    /**
     * El informe del club completo: todas las categorías a la vez. Cada una lee SIDGAD en paralelo (el ritmo lo pone el
     * cupo del informe del club en SidgadClient) y, apenas termina, pasa al análisis; los análisis van de a
     * {@link #CLUB_ANALYSES} para no pasar el límite de pedidos por minuto de Gemini.
     */
    private void runClub(Job job, ClubView club, List<ClubCategory> categories) {
        long start = System.nanoTime();
        try {
            int n = categories.size();
            AtomicInteger read = new AtomicInteger();
            AtomicInteger analyzed = new AtomicInteger();
            Runnable progress = () -> job.update(read.get() < n ? Status.EXTRACTING : Status.ANALYZING,
                    "Categorías leídas: " + read.get() + " de " + n + " · analizadas: " + analyzed.get() + " de " + n);
            progress.run();
            Semaphore analyses = new Semaphore(CLUB_ANALYSES);
            List<Future<CategoryPart>> parts = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (ClubCategory c : categories) {
                    parts.add(pool.submit(() -> {
                        ReportData data = extract(job, club, c.category(), c.option(), m -> { });
                        read.incrementAndGet();
                        progress.run();
                        AnalysisResult analysis = null;
                        if (!data.teams().isEmpty()) {
                            analyses.acquire();
                            try {
                                analysis = gemini.analyze(data).orElseGet(() -> rules.analyze(data));
                            } finally {
                                analyses.release();
                            }
                        }
                        analyzed.incrementAndGet();
                        progress.run();
                        return new CategoryPart(c, data, analysis);
                    }));
                }
            }
            Map<String, List<ClubReport.CategoryBlock>> byBranch = new LinkedHashMap<>();
            List<String> notPublished = new ArrayList<>();
            List<ReportData.Source> sources = new ArrayList<>();
            String logo = null;
            for (Future<CategoryPart> f : parts) {
                CategoryPart p = await(f);
                ReportData data = p.data();
                if (data.teams().isEmpty()) {
                    continue;
                }
                String label = label(p.category().category(), p.category().option());
                byBranch.computeIfAbsent(p.category().category().branchLabel(), k -> new ArrayList<>())
                        .add(new ClubReport.CategoryBlock(p.category().levelLabel(), data, data.teams().getFirst(),
                                p.analysis()));
                data.notPublished().forEach(np -> notPublished.add(label + ": " + np));
                sources.addAll(data.sources());
                logo = logo != null ? logo : data.club().logo();
            }
            job.update(Status.RENDERING, "Generando el PDF");
            ReportData.ClubInfo info = new ReportData.ClubInfo(club.id(), club.code(), club.name(), logo);
            ClubReport report = new ClubReport(info, clubPeriod(job.request()), LocalDate.now(clock),
                    job.request().federation(),
                    byBranch.entrySet().stream().map(e -> new ClubReport.Branch(e.getKey(), e.getValue())).toList(),
                    notPublished, sources);
            job.pdf = renderer.clubPdf(report);
            job.html = renderer.clubHtml(report, false);
            job.xlsx = excel.club(report);
            job.fileName = ("informe-club-" + club.code() + "-" + job.request().scope().name() + "-"
                    + job.request().year() + ".pdf").toLowerCase().replaceAll("[^a-z0-9.-]+", "-");
            job.update(Status.READY, "Informe listo");
            log.info("Informe del club {} ({} categorías) listo en {} s", club.code(), n,
                    Duration.ofNanos(System.nanoTime() - start).toSeconds());
        } catch (RuntimeException e) {
            log.error("Falló el informe del club {}", job.id(), e);
            job.update(Status.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Una categoría del informe del club: sus datos y su análisis (null si el club no juega en ella). */
    private record CategoryPart(ClubCategory category, ReportData data, AnalysisResult analysis) {
    }

    /** "Temporada 2026 · Apertura + Clausura", "Temporada 2026 · Apertura", "Últimos 5 partidos". */
    static String clubPeriod(Request r) {
        return switch (r.scope()) {
            case LAST_5 -> "Temporada " + r.year() + " · Últimos 5 partidos de cada categoría";
            default -> "Temporada " + r.year() + " · " + (r.scope().tournament() == null ? "Apertura + Clausura"
                    : r.scope().tournament().label());
        };
    }

    /** "Infantil B Femenino" (o "Infantil Femenino" si el club tiene un solo equipo). */
    static String label(Category category, CategoryOption option) {
        return category.levelLabel() + (option.letter() == null ? "" : " " + option.letter()) + " " + category.branchLabel();
    }

    public Optional<Job> find(UUID id, String owner) {
        return Optional.ofNullable(jobs.getIfPresent(id)).filter(j -> Objects.equals(j.owner, owner));
    }

    private void run(Job job, ClubView club, Category category, CategoryOption option) {
        try {
            ReportData data = extract(job, club, category, option, m -> job.update(Status.EXTRACTING, m));
            job.update(Status.ANALYZING, gemini.available() ? "Gemini está analizando los datos" : "Analizando los datos");
            AnalysisResult analysis = gemini.analyze(data).orElseGet(() -> rules.analyze(data));
            if (analysis.foulsAnalysis().isEmpty()) {
                analysis = analysis.withFoulsAnalysis(rules.foulsAnalysis(data));
            }
            job.update(Status.RENDERING, "Generando el PDF");
            job.pdf = renderer.pdf(data, analysis);
            job.html = renderer.html(data, analysis);
            // En Excel, la categoría como un informe de club de una sola categoría.
            String level = category.levelLabel() + (option.letter() == null ? "" : " " + option.letter());
            job.xlsx = excel.club(new ClubReport(data.club(), data.tournament(), data.generatedOn(),
                    job.request().federation(), List.of(new ClubReport.Branch(category.branchLabel(),
                    List.of(new ClubReport.CategoryBlock(level, data, data.teams().getFirst(), analysis)))),
                    data.notPublished(), data.sources()));
            job.fileName = fileName(club, category, option, job.request().scope());
            job.update(Status.READY, "Informe listo");
        } catch (RuntimeException e) {
            log.error("Falló el informe {}", job.id(), e);
            job.update(Status.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    ReportData extract(Job job, ClubView club, Category category, CategoryOption option, Consumer<String> progress) {
        Scope scope = job.request().scope();
        int year = job.request().year();
        Federation fed = job.request().federation();
        List<Competition> comps = catalog.competitionsFor(fed, club.id(), category, scope.tournament(), year);
        List<CompetitionData> data = new ArrayList<>();
        List<String> notPublished = new ArrayList<>();
        Map<Competition, List<Match>> clubMatches = new LinkedHashMap<>();
        int clubSheets;
        // Los pedidos a SIDGAD van en paralelo (cada uno tarda ~1,5 s, casi todo latencia del sitio). El límite de
        // pedidos simultáneos y por segundo lo pone SidgadClient, para no sobrecargar el sitio.
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            progress.accept("Leyendo tablas, calendarios y estadísticas");
            Map<Competition, List<Future<String>>> pages = new LinkedHashMap<>();
            for (Competition c : comps) {
                pages.put(c, List.of(
                        io.submit(() -> sidgad.standings(c.id())),
                        io.submit(() -> sidgad.calendar(c.id())),
                        io.submit(() -> sidgad.playerStats(c.id()))));
            }
            for (Competition c : comps) {
                String tournament = c.tournamentLabel();
                List<Future<String>> f = pages.get(c);
                String standings = published(await(f.get(0)), "Tabla de posiciones (" + tournament + ")", notPublished);
                String calendar = published(await(f.get(1)), "Calendario y resultados (" + tournament + ")", notPublished);
                String players = published(await(f.get(2)), "Estadísticas por jugador (" + tournament + ")", notPublished);
                List<Match> matches = SidgadParser.calendar(calendar);
                clubMatches.put(c, sheetsToRead(c, matches, club.id(), scope.lastMatches()));
                data.add(new CompetitionData(c,
                        SidgadParser.standings(standings), SidgadParser.reportDate(standings), matches,
                        SidgadParser.playerStats(players), new HashMap<>(), Set.of(), new HashMap<>()));
            }

            // Fichas: las de los partidos del club (minuto de goles, tarjetas y faltas) y las del resto de la liga
            // (solo para el promedio de faltas por equipo y partido). Quedan en cache 24 h.
            List<Map.Entry<CompetitionData, Match>> sheets = new ArrayList<>();
            for (CompetitionData cd : data) {
                clubMatches.get(cd.competition()).forEach(m -> sheets.add(Map.entry(cd, m)));
            }
            clubSheets = sheets.size();
            for (CompetitionData cd : data) {
                List<Match> own = clubMatches.get(cd.competition());
                cd.matches().stream()
                        .filter(m -> m.played() && m.idp() > 0 && !own.contains(m))
                        .forEach(m -> sheets.add(Map.entry(cd, m)));
            }
            AtomicInteger done = new AtomicInteger();
            progress.accept("Leyendo actas de partido (0/" + sheets.size() + ")");
            int ownSheets = clubSheets;
            List<Future<SheetRead>> reads = new ArrayList<>();
            for (int i = 0; i < sheets.size(); i++) {
                Map.Entry<CompetitionData, Match> e = sheets.get(i);
                boolean own = i < ownSheets;
                reads.add(io.submit(() -> {
                    String html = sidgad.gameSheet(e.getKey().competition().id(), e.getValue().idp());
                    progress.accept("Leyendo actas de partido (" + done.incrementAndGet() + "/" + sheets.size() + ")");
                    return new SheetRead(SidgadParser.gameEvents(html), own ? SidgadParser.lineups(html) : List.of());
                }));
            }
            // Jugadores de cada equipo del club por competición: los de sus planillas y los de la tabla de SIDGAD.
            Map<Competition, Map<Integer, Integer>> teamOf = new LinkedHashMap<>();
            Map<Integer, String> names = new HashMap<>();
            Map<Integer, int[]> roles = new HashMap<>();   // jugador -> {actas como arquero, actas de jugador}
            for (int i = 0; i < sheets.size(); i++) {
                CompetitionData cd = sheets.get(i).getKey();
                Match m = sheets.get(i).getValue();
                SheetRead read = await(reads.get(i));
                cd.sheets().put(m.idp(), read.events());
                Map<Integer, Integer> own = teamOf.computeIfAbsent(cd.competition(), k -> new LinkedHashMap<>());
                for (TeamRef t : cd.competition().teams()) {
                    if (t.clubId() == club.id() && m.involves(t.teamId())) {
                        boolean home = m.homeTeamId() == t.teamId();
                        read.lineups().stream().filter(p -> p.home() == home).forEach(p -> {
                            own.putIfAbsent(p.playerId(), t.teamId());
                            names.putIfAbsent(p.playerId(), p.name());
                            roles.computeIfAbsent(p.playerId(), k -> new int[2])[p.goalkeeper() ? 0 : 1]++;
                        });
                        // El arquero titular del partido ("P" en el cinco inicial).
                        read.lineups().stream().filter(p -> p.home() == home && p.starter() && p.goalkeeper())
                                .findFirst().ifPresent(p -> cd.startingKeepers()
                                        .put(m.idp(), new StatsEngine.Keeper(p.playerId(), p.name())));
                    }
                }
            }
            for (CompetitionData cd : data) {
                Set<Integer> clubTeams = cd.competition().teams().stream()
                        .filter(t -> t.clubId() == club.id()).map(TeamRef::teamId).collect(Collectors.toSet());
                Map<Integer, Integer> own = teamOf.computeIfAbsent(cd.competition(), k -> new LinkedHashMap<>());
                cd.players().stream().filter(p -> clubTeams.contains(p.teamId())).forEach(p -> {
                    own.putIfAbsent(p.playerId(), p.teamId());
                    names.putIfAbsent(p.playerId(), p.name());
                });
            }
            replaceWithSeasonProfiles(progress, io, club, data, teamOf, names, notPublished);
            // Arqueros: figuran con "P" en al menos 2 planillas, o en la mayoría de las suyas (con pocas actas).
            Set<Integer> goalkeepers = roles.entrySet().stream()
                    .filter(e -> e.getValue()[0] >= 2 || (e.getValue()[0] > 0 && e.getValue()[0] >= e.getValue()[1]))
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            for (int i = 0; i < data.size(); i++) {
                CompetitionData cd = data.get(i);
                data.set(i, new CompetitionData(cd.competition(), cd.standings(), cd.standingsDate(), cd.matches(),
                        cd.players(), cd.sheets(), goalkeepers, cd.startingKeepers()));
            }
        }
        // Las actas faltantes del club ya se informan en el PDF ("N actas de M partidos"); acá solo se resume.
        long missingSheets = data.stream()
                .flatMap(cd -> clubMatches.get(cd.competition()).stream().map(m -> cd.sheets().get(m.idp())))
                .filter(List::isEmpty).count();
        if (missingSheets > 0) {
            notPublished.add("Actas de partido: " + missingSheets + " de " + clubSheets);
        }
        String tournaments = comps.stream().map(Competition::tournamentLabel).distinct().collect(Collectors.joining(" + "));
        String label = scope == Scope.LAST_5
                ? "Últimos 5 partidos (" + tournaments + ")"
                : "Temporada " + year + " · " + tournaments;
        return StatsEngine.build(club, label(category, option), label, data, LocalDate.now(clock), scope.lastMatches(),
                        option.teamKey(club.code()))
                .withNotPublished(notPublished)
                .withFederation(fed)
                .withLogo(Crests.tile(sidgad.clubLogo(logoFile(comps, club.id()))));
    }

    /**
     * Archivo del escudo del club según teams_array: el más usado entre sus equipos (SIDGAD a veces le pone a un
     * equipo el logo de otro club, pero nunca a la mayoría).
     */
    public static String logoFile(List<Competition> comps, int clubId) {
        return comps.stream()
                .flatMap(c -> c.teams().stream())
                .filter(t -> t.clubId() == clubId && !t.logo().isBlank())
                .collect(Collectors.groupingBy(TeamRef::logo, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** Espera el resultado de un pedido en paralelo; si falló, relanza el error original (p. ej. SidgadException). */
    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido leyendo SIDGAD", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * Desde cuándo sirve una ficha de temporada guardada: bajada después del día del último partido del club (su ficha
     * ya lo incluye) o, si el club jugó hoy, en las últimas 2 horas. Sin partidos jugados, de las últimas 24 horas.
     */
    static Instant profilesNotBefore(ClubView club, List<CompetitionData> data, Instant now) {
        LocalDate last = data.stream().flatMap(cd -> {
                    Set<Integer> own = cd.competition().teams().stream().filter(t -> t.clubId() == club.id())
                            .map(TeamRef::teamId).collect(Collectors.toSet());
                    return cd.matches().stream().filter(m -> m.played() && m.date() != null
                            && (own.contains(m.homeTeamId()) || own.contains(m.awayTeamId())));
                })
                .map(Match::date).max(Comparator.naturalOrder()).orElse(null);
        if (last == null) {
            return now.minus(Duration.ofHours(24));
        }
        Instant dayAfter = last.plusDays(1).atStartOfDay(java.time.ZoneId.of("America/Argentina/Buenos_Aires")).toInstant();
        Instant twoHoursAgo = now.minus(Duration.ofHours(2));
        return dayAfter.isBefore(twoHoursAgo) ? dayAfter : twoHoursAgo;
    }

    /** Lo que se usa de un acta: sus eventos y, en los partidos del club, las planillas. */
    private record SheetRead(List<MatchEvent> events, List<LineupPlayer> lineups) {
    }

    /**
     * Las estadísticas por jugador del club salen de la ficha de temporada de cada jugador: la tabla de SIDGAD solo
     * suma los partidos con acta (y a veces no se publica), así que sus goles no llegan a los del equipo. Quien no
     * tenga ficha conserva su fila de la tabla. Los demás equipos de la liga siguen con la tabla de SIDGAD.
     */
    private void replaceWithSeasonProfiles(Consumer<String> progress, ExecutorService io, ClubView club,
                                           List<CompetitionData> data,
                                           Map<Competition, Map<Integer, Integer>> teamOf, Map<Integer, String> names,
                                           List<String> notPublished) {
        Map<Integer, Set<Integer>> seasonsOf = new LinkedHashMap<>();
        teamOf.forEach((c, players) -> players.keySet()
                .forEach(id -> seasonsOf.computeIfAbsent(id, k -> new HashSet<>()).add(c.seasonId())));
        if (seasonsOf.isEmpty()) {
            return;
        }
        progress.accept("Leyendo las fichas de temporada de los jugadores del club");
        Instant notBefore = profilesNotBefore(club, data, clock.instant());
        Map<Integer, Future<List<SeasonMatch>>> pending = new LinkedHashMap<>();
        seasonsOf.forEach((id, seasons) -> pending.put(id, io.submit(() -> {
            List<SeasonMatch> rows = new ArrayList<>();
            for (int season : seasons) {
                try {
                    rows.addAll(SidgadParser.seasonMatches(sidgad.playerSeason(id, season, notBefore)));
                } catch (SidgadClient.SidgadException e) {
                    log.info("Sin ficha de temporada del jugador {}: {}", id, e.getMessage());
                }
            }
            return rows;
        })));
        Map<Integer, List<SeasonMatch>> profiles = new HashMap<>();
        pending.forEach((id, f) -> profiles.put(id, await(f)));
        for (int i = 0; i < data.size(); i++) {
            CompetitionData cd = data.get(i);
            Set<Integer> clubTeams = cd.competition().teams().stream()
                    .filter(t -> t.clubId() == club.id()).map(TeamRef::teamId).collect(Collectors.toSet());
            List<PlayerStats> ours = SeasonScorers.forCompetition(cd.competition(), club.id(),
                    teamOf.getOrDefault(cd.competition(), Map.of()), names, profiles, cd.players());
            if (ours.isEmpty()) {
                continue;
            }
            List<PlayerStats> players = new ArrayList<>(cd.players().stream()
                    .filter(p -> !clubTeams.contains(p.teamId())).toList());
            players.addAll(ours);
            data.set(i, new CompetitionData(cd.competition(), cd.standings(), cd.standingsDate(), cd.matches(),
                    players, cd.sheets(), cd.goalkeepers(), cd.startingKeepers()));
            // Con las fichas de temporada ya no falta: la tabla de SIDGAD de esa competición no hace falta.
            notPublished.remove("Estadísticas por jugador (" + cd.competition().tournamentLabel() + ")");
        }
    }

    /** Si SIDGAD no publicó el fragmento (404), se anota para el PDF y se sigue con contenido vacío. */
    private static String published(String body, String what, List<String> notPublished) {
        if (SidgadClient.NOT_PUBLISHED.equals(body)) {
            notPublished.add(what);
        }
        return body;
    }

    /**
     * Partidos jugados del club con acta para leer. Con "últimos N" alcanza con los últimos N de cada equipo
     * en cada competición: los últimos N de la temporada están siempre dentro de ese conjunto.
     */
    static List<Match> sheetsToRead(Competition c, List<Match> matches, int clubId, int lastMatches) {
        List<Match> result = new ArrayList<>();
        for (TeamRef t : c.teams()) {
            if (t.clubId() != clubId) {
                continue;
            }
            List<Match> played = matches.stream()
                    .filter(m -> m.played() && m.idp() > 0 && m.involves(t.teamId()))
                    .sorted(Comparator.comparing(Match::date, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Match::round))
                    .toList();
            int skip = lastMatches > 0 ? Math.max(0, played.size() - lastMatches) : 0;
            played.subList(skip, played.size()).stream().filter(m -> !result.contains(m)).forEach(result::add);
        }
        return result;
    }

    private static String fileName(ClubView club, Category category, CategoryOption option, Scope scope) {
        return ("informe-" + option.teamKey(club.code()) + "-" + category.key() + "-" + scope.name() + ".pdf")
                .toLowerCase().replaceAll("[^a-z0-9.-]+", "-");
    }
}
