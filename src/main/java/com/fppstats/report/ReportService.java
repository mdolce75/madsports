package com.fppstats.report;

import com.fppstats.analysis.AnalysisResult;
import com.fppstats.analysis.GeminiAnalyst;
import com.fppstats.analysis.RuleBasedAnalyst;
import com.fppstats.catalog.CatalogService;
import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.sidgad.SidgadClient;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Competition.Category;
import com.fppstats.sidgad.model.Competition.Torneo;
import com.fppstats.sidgad.model.Models.Match;
import com.fppstats.sidgad.model.Models.TeamRef;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Orquesta un informe: extracción (SIDGAD) → cálculo (StatsEngine) → análisis (Gemini o reglas) → PDF.
 * Corre en segundo plano; el cliente consulta el estado del job y descarga el PDF al terminar.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    public enum Status { PENDIENTE, EXTRAYENDO, ANALIZANDO, GENERANDO_PDF, LISTO, ERROR }

    /** Período que cubre el informe. */
    public enum Scope {
        APERTURA(Torneo.APERTURA, 0, "Apertura"),
        CLAUSURA(Torneo.CLAUSURA, 0, "Clausura"),
        TEMPORADA(null, 0, "Apertura + Clausura"),
        ULTIMOS_5(null, 5, "Últimos 5 partidos");

        private final Torneo torneo;
        private final int lastMatches;
        private final String label;

        Scope(Torneo torneo, int lastMatches, String label) {
            this.torneo = torneo;
            this.lastMatches = lastMatches;
            this.label = label;
        }

        public Torneo torneo() { return torneo; }
        public int lastMatches() { return lastMatches; }
        public String label() { return label; }

        public static Scope parse(String value) {
            if (value == null || value.isBlank()) {
                return TEMPORADA;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Período inválido: " + value + ". Válidos: "
                        + Arrays.toString(values()));
            }
        }
    }

    /** Parámetros del informe: club, categoría (nivel), rama y período. */
    /** @param year año (temporada) ya validado contra los ofrecidos */
    public record Request(int clubId, String category, String branch, Scope scope, int year) {
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
    private final GeminiAnalyst gemini;
    private final RuleBasedAnalyst rules;
    private final PdfRenderer renderer;
    private final Clock clock;
    private final Cache<UUID, Job> jobs = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public ReportService(CatalogService catalog, SidgadClient sidgad, GeminiAnalyst gemini,
                         RuleBasedAnalyst rules, PdfRenderer renderer, Clock clock) {
        this.catalog = catalog;
        this.sidgad = sidgad;
        this.gemini = gemini;
        this.rules = rules;
        this.renderer = renderer;
        this.clock = clock;
    }

    /** Valida los parámetros (falla rápido) y encola el informe. */
    public Job submit(Request request) {
        if (!catalog.available(request.year())) {
            throw new IllegalArgumentException(CatalogService.unavailableMessage(request.year()));
        }
        ClubView club = catalog.club(request.clubId(), request.year());
        Category category = catalog.category(request.category(), request.branch());
        Torneo torneo = request.scope().torneo();
        if (catalog.competitionsFor(club.id(), category, torneo, request.year()).isEmpty()) {
            throw new IllegalArgumentException(club.name() + " no participa en " + category.label()
                    + (torneo == null ? "" : " - " + torneo.label()) + " en " + request.year() + ".");
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
            job.update(Status.ANALIZANDO, gemini.available() ? "Gemini está analizando los datos" : "Analizando los datos");
            AnalysisResult analysis = gemini.analyze(data).orElseGet(() -> rules.analyze(data));
            job.update(Status.GENERANDO_PDF, "Generando el PDF");
            job.pdf = renderer.pdf(data, analysis);
            job.fileName = fileName(club, category, job.request().scope());
            job.update(Status.LISTO, "Informe listo");
        } catch (RuntimeException e) {
            log.error("Falló el informe {}", job.id(), e);
            job.update(Status.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    ReportData extract(Job job, ClubView club, Category category) {
        Scope scope = job.request().scope();
        int year = job.request().year();
        List<Competition> comps = catalog.competitionsFor(club.id(), category, scope.torneo(), year);
        List<CompetitionData> data = new ArrayList<>();
        List<String> notPublished = new ArrayList<>();
        Map<Competition, List<Match>> clubMatches = new LinkedHashMap<>();
        for (Competition c : comps) {
            job.update(Status.EXTRAYENDO, "Leyendo " + c.name());
            String torneo = c.torneoLabel();
            String standings = published(sidgad.standings(c.id()), "Tabla de posiciones (" + torneo + ")", notPublished);
            String calendar = published(sidgad.calendar(c.id()), "Calendario y resultados (" + torneo + ")", notPublished);
            String players = published(sidgad.playerStats(c.id()), "Estadísticas por jugador (" + torneo + ")", notPublished);
            List<Match> matches = SidgadParser.calendar(calendar);
            clubMatches.put(c, sheetsToRead(c, matches, club.id(), scope.lastMatches()));
            data.add(new CompetitionData(c,
                    SidgadParser.standings(standings), SidgadParser.reportDate(standings), matches,
                    SidgadParser.playerStats(players), new HashMap<>()));
        }
        // Actas: una por partido jugado del club (minuto de goles y tarjetas).
        int total = clubMatches.values().stream().mapToInt(List::size).sum();
        int done = 0;
        for (CompetitionData cd : data) {
            for (Match m : clubMatches.get(cd.competition())) {
                job.update(Status.EXTRAYENDO, "Leyendo actas de partido (" + (++done) + "/" + total + ")");
                cd.sheets().put(m.idp(), SidgadParser.gameEvents(sidgad.gameSheet(cd.competition().id(), m.idp())));
            }
        }
        // Las actas faltantes ya se informan en el PDF ("N actas de M partidos"); acá solo se resume.
        long missingSheets = data.stream().flatMap(cd -> cd.sheets().values().stream()).filter(List::isEmpty).count();
        if (missingSheets > 0) {
            notPublished.add("Actas de partido: " + missingSheets + " de " + total);
        }
        String torneos = comps.stream().map(Competition::torneoLabel).distinct().collect(Collectors.joining(" + "));
        String label = scope == Scope.ULTIMOS_5
                ? "Últimos 5 partidos (" + torneos + ")"
                : "Temporada " + year + " · " + torneos;
        return StatsEngine.build(club, category.label(), label, data, LocalDate.now(clock), scope.lastMatches())
                .withNotPublished(notPublished);
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

    private static String fileName(ClubView club, Category category, Scope scope) {
        return ("informe-" + club.code() + "-" + category.key() + "-" + scope.name() + ".pdf")
                .toLowerCase().replaceAll("[^a-z0-9.-]+", "-");
    }
}
