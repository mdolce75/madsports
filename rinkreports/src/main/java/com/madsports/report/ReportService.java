package com.madsports.report;

import com.madsports.analysis.AnalysisResult;
import com.madsports.analysis.GeminiAnalyst;
import com.madsports.analysis.RuleBasedAnalyst;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.CategoryOption;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Competition.Tournament;
import com.madsports.sidgad.model.Federation;
import com.madsports.stats.ReportData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Orquesta un informe: extracción (SIDGAD, en {@link CategoryExtractor}) → cálculo (StatsEngine) → análisis
 * (Gemini o reglas) → PDF.
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
    private final com.madsports.catalog.RosterService roster;
    private final CategoryExtractor extractor;
    private final Cache<UUID, Job> jobs = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    /** Análisis de Gemini a la vez en el informe del club (el plan gratuito limita los pedidos por minuto). */
    static final int CLUB_ANALYSES = 3;

    public ReportService(CatalogService catalog, SidgadClient sidgad, GeminiAnalyst gemini,
                         RuleBasedAnalyst rules, PdfRenderer renderer, ExcelExporter excel, Clock clock,
                         com.madsports.catalog.RosterService roster) {
        this.roster = roster;
        this.catalog = catalog;
        this.sidgad = sidgad;
        this.gemini = gemini;
        this.rules = rules;
        this.renderer = renderer;
        this.excel = excel;
        this.clock = clock;
        this.extractor = new CategoryExtractor(catalog, sidgad, clock);
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

    /**
     * Las categorías del club que juegan en el período y tienen jugadores en SIDGAD (una sin jugadores no se ofrece ni
     * entra en el informe): Masculino y después Femenino, cada una de Infantil a Primera. */
    List<ClubCategory> clubCategories(ClubView club, Federation fed, Scope scope, int year) {
        List<ClubCategory> out = new ArrayList<>();
        ClubView offered = roster.withPlayers(fed, club, year);
        for (String branch : List.of("MASCULINO", "FEMENINO")) {
            for (CatalogService.LevelView l : offered.categories()) {
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
                        ReportData data = extractor.extract(job.request(), club, c.category(), c.option(), m -> { });
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
                CategoryPart p = CategoryExtractor.await(f);
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
            ReportData data = extractor.extract(job.request(), club, category, option, m -> job.update(Status.EXTRACTING, m));
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

    private static String fileName(ClubView club, Category category, CategoryOption option, Scope scope) {
        return ("informe-" + option.teamKey(club.code()) + "-" + category.key() + "-" + scope.name() + ".pdf")
                .toLowerCase().replaceAll("[^a-z0-9.-]+", "-");
    }
}