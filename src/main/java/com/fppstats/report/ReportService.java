package com.fppstats.report;

import com.fppstats.analysis.AnalysisResult;
import com.fppstats.analysis.ClaudeAnalyst;
import com.fppstats.analysis.RuleBasedAnalyst;
import com.fppstats.catalog.CatalogService;
import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.sidgad.SidgadClient;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Competition.Category;
import com.fppstats.sidgad.parse.SidgadParser;
import com.fppstats.stats.ReportData;
import com.fppstats.stats.StatsEngine;
import com.fppstats.stats.StatsEngine.CompetitionData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Orquesta un informe: extracción (SIDGAD) → cálculo (StatsEngine) → análisis (Claude o reglas) → PDF.
 * Corre en segundo plano; el cliente consulta el estado del job y descarga el PDF al terminar.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    public enum Status { PENDIENTE, EXTRAYENDO, ANALIZANDO, GENERANDO_PDF, LISTO, ERROR }

    /** Parámetros del informe: club, categoría (nivel) y rama. Cubre la temporada completa. */
    public record Request(int clubId, String category, String branch) {
    }

    public static final class Job {
        private final UUID id = UUID.randomUUID();
        private final Request request;
        private volatile Status status = Status.PENDIENTE;
        private volatile String message = "En cola";
        private volatile byte[] pdf;
        private volatile String fileName;

        Job(Request request) {
            this.request = request;
        }

        public UUID id() { return id; }
        public Request request() { return request; }
        public Status status() { return status; }
        public String message() { return message; }
        public byte[] pdf() { return pdf; }
        public String fileName() { return fileName; }

        void update(Status status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    private final CatalogService catalog;
    private final SidgadClient sidgad;
    private final ClaudeAnalyst claude;
    private final RuleBasedAnalyst rules;
    private final PdfRenderer renderer;
    private final Clock clock;
    private final Cache<UUID, Job> jobs = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public ReportService(CatalogService catalog, SidgadClient sidgad, ClaudeAnalyst claude,
                         RuleBasedAnalyst rules, PdfRenderer renderer, Clock clock) {
        this.catalog = catalog;
        this.sidgad = sidgad;
        this.claude = claude;
        this.rules = rules;
        this.renderer = renderer;
        this.clock = clock;
    }

    /** Valida los parámetros (falla rápido) y encola el informe. */
    public Job submit(Request request) {
        ClubView club = catalog.club(request.clubId());
        Category category = catalog.category(request.category(), request.branch());
        if (catalog.competitionsFor(club.id(), category).isEmpty()) {
            throw new IllegalArgumentException(club.name() + " no participa en " + category.label() + " esta temporada.");
        }
        Job job = new Job(request);
        jobs.put(job.id(), job);
        executor.submit(() -> run(job, club, category));
        return job;
    }

    public Optional<Job> find(UUID id) {
        return Optional.ofNullable(jobs.getIfPresent(id));
    }

    private void run(Job job, ClubView club, Category category) {
        try {
            ReportData data = extract(job, club, category);
            job.update(Status.ANALIZANDO, claude.available() ? "Claude está analizando los datos" : "Analizando los datos");
            AnalysisResult analysis = claude.analyze(data).orElseGet(() -> rules.analyze(data));
            job.update(Status.GENERANDO_PDF, "Generando el PDF");
            job.pdf = renderer.pdf(data, analysis);
            job.fileName = fileName(club, category);
            job.update(Status.LISTO, "Informe listo");
        } catch (RuntimeException e) {
            log.error("Falló el informe {}", job.id(), e);
            job.update(Status.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    ReportData extract(Job job, ClubView club, Category category) {
        List<Competition> comps = catalog.competitionsFor(club.id(), category);
        List<CompetitionData> data = new ArrayList<>();
        for (Competition c : comps) {
            job.update(Status.EXTRAYENDO, "Leyendo " + c.name());
            String standings = sidgad.standings(c.id());
            data.add(new CompetitionData(c,
                    SidgadParser.standings(standings), SidgadParser.reportDate(standings),
                    SidgadParser.calendar(sidgad.calendar(c.id())),
                    SidgadParser.playerStats(sidgad.playerStats(c.id()))));
        }
        String torneos = comps.stream().map(Competition::torneoLabel).distinct().collect(Collectors.joining(" + "));
        return StatsEngine.build(club, category.label(), "Temporada " + LocalDate.now(clock).getYear() + " (" + torneos + ")",
                data, LocalDate.now(clock));
    }

    private static String fileName(ClubView club, Category category) {
        return ("informe-" + club.code() + "-" + category.key() + ".pdf").toLowerCase().replaceAll("[^a-z0-9.-]+", "-");
    }
}
