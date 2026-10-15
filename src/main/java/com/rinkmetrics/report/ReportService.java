package com.rinkmetrics.report;

import com.rinkmetrics.analysis.AnalysisResult;
import com.rinkmetrics.analysis.GeminiAnalyst;
import com.rinkmetrics.analysis.RuleBasedAnalyst;
import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.CategoryOption;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.sidgad.SidgadClient;
import com.rinkmetrics.sidgad.model.Competition;
import com.rinkmetrics.sidgad.model.Competition.Category;
import com.rinkmetrics.sidgad.model.Competition.Torneo;
import com.rinkmetrics.sidgad.model.Models.LineupPlayer;
import com.rinkmetrics.sidgad.model.Models.Match;
import com.rinkmetrics.sidgad.model.Models.MatchEvent;
import com.rinkmetrics.sidgad.model.Models.PlayerStats;
import com.rinkmetrics.sidgad.model.Models.SeasonMatch;
import com.rinkmetrics.sidgad.model.Models.TeamRef;
import com.rinkmetrics.sidgad.parse.SidgadParser;
import com.rinkmetrics.stats.ReportData;
import com.rinkmetrics.stats.StatsEngine;
import com.rinkmetrics.stats.StatsEngine.CompetitionData;
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
import java.util.concurrent.atomic.AtomicInteger;
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
        private final String owner;
        private volatile Status status = Status.PENDIENTE;
        private volatile String message = "En cola";
        private volatile byte[] pdf;
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
    /** @param owner usuario que pide el informe: solo él puede consultarlo y bajarlo */
    public Job submit(Request request, String owner) {
        if (!catalog.available(request.year())) {
            throw new IllegalArgumentException(CatalogService.unavailableMessage(request.year()));
        }
        ClubView club = catalog.club(request.clubId(), request.year());
        CategoryOption option = CategoryOption.parse(request.category());
        Category category = catalog.category(option.level(), request.branch());
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
        Torneo torneo = request.scope().torneo();
        if (catalog.competitionsFor(club.id(), category, torneo, request.year()).isEmpty()) {
            throw new IllegalArgumentException(club.name() + " no participa en " + label(category, option)
                    + (torneo == null ? "" : " - " + torneo.label()) + " en " + request.year() + ".");
        }
        Job job = new Job(request, owner);
        jobs.put(job.id(), job);
        executor.submit(() -> run(job, club, category, option));
        return job;
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
            ReportData data = extract(job, club, category, option);
            job.update(Status.ANALIZANDO, gemini.available() ? "Gemini está analizando los datos" : "Analizando los datos");
            AnalysisResult analysis = gemini.analyze(data).orElseGet(() -> rules.analyze(data));
            if (analysis.foulsAnalysis().isEmpty()) {
                analysis = analysis.withFoulsAnalysis(rules.foulsAnalysis(data));
            }
            job.update(Status.GENERANDO_PDF, "Generando el PDF");
            job.pdf = renderer.pdf(data, analysis);
            job.fileName = fileName(club, category, option, job.request().scope());
            job.update(Status.LISTO, "Informe listo");
        } catch (RuntimeException e) {
            log.error("Falló el informe {}", job.id(), e);
            job.update(Status.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    ReportData extract(Job job, ClubView club, Category category, CategoryOption option) {
        Scope scope = job.request().scope();
        int year = job.request().year();
        List<Competition> comps = catalog.competitionsFor(club.id(), category, scope.torneo(), year);
        List<CompetitionData> data = new ArrayList<>();
        List<String> notPublished = new ArrayList<>();
        Map<Competition, List<Match>> clubMatches = new LinkedHashMap<>();
        int clubSheets;
        // Los pedidos a SIDGAD van en paralelo (cada uno tarda ~1,5 s, casi todo latencia del sitio). El límite de
        // pedidos simultáneos y por segundo lo pone SidgadClient, para no sobrecargar el sitio.
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            job.update(Status.EXTRAYENDO, "Leyendo tablas, calendarios y estadísticas");
            Map<Competition, List<Future<String>>> pages = new LinkedHashMap<>();
            for (Competition c : comps) {
                pages.put(c, List.of(
                        io.submit(() -> sidgad.standings(c.id())),
                        io.submit(() -> sidgad.calendar(c.id())),
                        io.submit(() -> sidgad.playerStats(c.id()))));
            }
            for (Competition c : comps) {
                String torneo = c.torneoLabel();
                List<Future<String>> f = pages.get(c);
                String standings = published(await(f.get(0)), "Tabla de posiciones (" + torneo + ")", notPublished);
                String calendar = published(await(f.get(1)), "Calendario y resultados (" + torneo + ")", notPublished);
                String players = published(await(f.get(2)), "Estadísticas por jugador (" + torneo + ")", notPublished);
                List<Match> matches = SidgadParser.calendar(calendar);
                clubMatches.put(c, sheetsToRead(c, matches, club.id(), scope.lastMatches()));
                data.add(new CompetitionData(c,
                        SidgadParser.standings(standings), SidgadParser.reportDate(standings), matches,
                        SidgadParser.playerStats(players), new HashMap<>()));
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
            job.update(Status.EXTRAYENDO, "Leyendo actas de partido (0/" + sheets.size() + ")");
            int ownSheets = clubSheets;
            List<Future<SheetRead>> reads = new ArrayList<>();
            for (int i = 0; i < sheets.size(); i++) {
                Map.Entry<CompetitionData, Match> e = sheets.get(i);
                boolean own = i < ownSheets;
                reads.add(io.submit(() -> {
                    String html = sidgad.gameSheet(e.getKey().competition().id(), e.getValue().idp());
                    job.update(Status.EXTRAYENDO,
                            "Leyendo actas de partido (" + done.incrementAndGet() + "/" + sheets.size() + ")");
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
            replaceWithSeasonProfiles(job, io, club, data, teamOf, names, notPublished);
            // Arqueros: figuran con "P" en al menos 2 planillas, o en la mayoría de las suyas (con pocas actas).
            Set<Integer> goalkeepers = roles.entrySet().stream()
                    .filter(e -> e.getValue()[0] >= 2 || (e.getValue()[0] > 0 && e.getValue()[0] >= e.getValue()[1]))
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            for (int i = 0; i < data.size(); i++) {
                CompetitionData cd = data.get(i);
                data.set(i, new CompetitionData(cd.competition(), cd.standings(), cd.standingsDate(), cd.matches(),
                        cd.players(), cd.sheets(), goalkeepers));
            }
        }
        // Las actas faltantes del club ya se informan en el PDF ("N actas de M partidos"); acá solo se resume.
        long missingSheets = data.stream()
                .flatMap(cd -> clubMatches.get(cd.competition()).stream().map(m -> cd.sheets().get(m.idp())))
                .filter(List::isEmpty).count();
        if (missingSheets > 0) {
            notPublished.add("Actas de partido: " + missingSheets + " de " + clubSheets);
        }
        String torneos = comps.stream().map(Competition::torneoLabel).distinct().collect(Collectors.joining(" + "));
        String label = scope == Scope.ULTIMOS_5
                ? "Últimos 5 partidos (" + torneos + ")"
                : "Temporada " + year + " · " + torneos;
        return StatsEngine.build(club, label(category, option), label, data, LocalDate.now(clock), scope.lastMatches(),
                        option.teamKey(club.code()))
                .withNotPublished(notPublished)
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

    /** Lo que se usa de un acta: sus eventos y, en los partidos del club, las planillas. */
    private record SheetRead(List<MatchEvent> events, List<LineupPlayer> lineups) {
    }

    /**
     * Las estadísticas por jugador del club salen de la ficha de temporada de cada jugador: la tabla de SIDGAD solo
     * suma los partidos con acta (y a veces no se publica), así que sus goles no llegan a los del equipo. Quien no
     * tenga ficha conserva su fila de la tabla. Los demás equipos de la liga siguen con la tabla de SIDGAD.
     */
    private void replaceWithSeasonProfiles(Job job, ExecutorService io, ClubView club, List<CompetitionData> data,
                                           Map<Competition, Map<Integer, Integer>> teamOf, Map<Integer, String> names,
                                           List<String> notPublished) {
        Map<Integer, Set<Integer>> seasonsOf = new LinkedHashMap<>();
        teamOf.forEach((c, players) -> players.keySet()
                .forEach(id -> seasonsOf.computeIfAbsent(id, k -> new HashSet<>()).add(c.seasonId())));
        if (seasonsOf.isEmpty()) {
            return;
        }
        job.update(Status.EXTRAYENDO, "Leyendo las fichas de temporada de los jugadores del club");
        Map<Integer, Future<List<SeasonMatch>>> pending = new LinkedHashMap<>();
        seasonsOf.forEach((id, seasons) -> pending.put(id, io.submit(() -> {
            List<SeasonMatch> rows = new ArrayList<>();
            for (int season : seasons) {
                try {
                    rows.addAll(SidgadParser.seasonMatches(sidgad.playerSeason(id, season)));
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
                    players, cd.sheets()));
            // Con las fichas de temporada ya no falta: la tabla de SIDGAD de esa competición no hace falta.
            notPublished.remove("Estadísticas por jugador (" + cd.competition().torneoLabel() + ")");
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
