package com.madsports.player;

import com.madsports.catalog.ClubTeams;
import com.madsports.catalog.RosterService;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.player.PlayerReport.Line;
import com.madsports.player.PlayerReport.MatchRow;
import com.madsports.report.Crests;
import com.madsports.report.ExcelExporter;
import com.madsports.report.PdfRenderer;
import com.madsports.report.ReportService;
import com.madsports.report.ReportService.Scope;
import com.madsports.report.ReportService.Status;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.SeasonMatch;
import com.madsports.stats.ShortHandedCalc;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.parse.SidgadParser;
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
import java.util.Comparator;
import java.util.HashMap;
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

    public static final class Job {
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

        void update(Status status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    /**
     * Lo leído de SIDGAD para un equipo del club en una competición: sus partidos jugados, su planilla en cada uno y
     * los eventos del acta (goles y tarjetas con minuto e {@code id_player}).
     *
     * @param summaries      partidos sin acta en que figura el jugador, según su ficha de temporada (por idp)
     * @param summaryLoaded  se pudo leer su ficha de temporada: un partido sin acta que no figura ahí no lo jugó
     */
    record TeamInput(Competition competition, String category, int teamId, List<Match> matches,
                     Map<Integer, List<LineupPlayer>> sheets, Map<Integer, List<MatchEvent>> events,
                     Map<Integer, SeasonMatch> summaries, boolean summaryLoaded) {

        TeamInput(Competition competition, String category, int teamId, List<Match> matches,
                  Map<Integer, List<LineupPlayer>> sheets, Map<Integer, List<MatchEvent>> events) {
            this(competition, category, teamId, matches, sheets, events, Map.of(), false);
        }

        TeamInput(Competition competition, String category, int teamId, List<Match> matches,
                  Map<Integer, List<LineupPlayer>> sheets) {
            this(competition, category, teamId, matches, sheets, Map.of());
        }

        TeamInput withSummaries(Map<Integer, SeasonMatch> summaries) {
            return new TeamInput(competition, category, teamId, matches, sheets, events, summaries, true);
        }

        /** ¿El jugador figura en este equipo, en alguna planilla o en su ficha de temporada? */
        boolean playedBy(int playerId) {
            return !summaries.isEmpty()
                    || sheets.values().stream().anyMatch(s -> s.stream().anyMatch(p -> p.playerId() == playerId));
        }
    }

    static final int BUCKET_MINUTES = 5;
    /** Período 10 de SIDGAD = descanso: cuenta como inicio del 2do tiempo. */
    static final int HALFTIME_PERIOD = 10;

    private final CatalogService catalog;
    private final SidgadClient sidgad;
    private final PdfRenderer renderer;
    private final ExcelExporter excel;
    private final Clock clock;
    private final Cache<UUID, Job> jobs = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public PlayerReportService(CatalogService catalog, SidgadClient sidgad, PdfRenderer renderer, ExcelExporter excel,
                               Clock clock) {
        this.catalog = catalog;
        this.sidgad = sidgad;
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
        Map<Competition, Future<String>> scorerTables = new LinkedHashMap<>();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            job.update(Status.EXTRACTING, "Leyendo los calendarios de los equipos del club");
            Map<Competition, Future<String>> calendars = new LinkedHashMap<>();
            comps.forEach(c -> calendars.put(c, io.submit(() -> sidgad.calendar(c.id()))));
            // La tabla de goleadores de cada torneo, para su lugar en la liga.
            comps.forEach(c -> scorerTables.put(c, io.submit(() -> sidgad.playerStats(c.id()))));
            Map<Competition, List<Match>> calendar = new LinkedHashMap<>();
            for (Competition c : comps) {
                calendar.put(c, SidgadParser.calendar(ClubTeams.await(calendars.get(c))));
            }

            Map<Integer, Competition> toRead = new LinkedHashMap<>();
            for (Competition c : comps) {
                for (TeamRef t : ClubTeams.own(c, club)) {
                    teamMatches(calendar.get(c), t.teamId()).forEach(m -> toRead.putIfAbsent(m.idp(), c));
                }
            }
            AtomicInteger done = new AtomicInteger();
            job.update(Status.EXTRACTING, "Leyendo actas de partido (0/" + toRead.size() + ")");
            Map<Integer, Future<String>> sheets = new LinkedHashMap<>();
            toRead.forEach((idp, c) -> sheets.put(idp, io.submit(() -> {
                String html = sidgad.gameSheet(c.id(), idp);
                job.update(Status.EXTRACTING, "Leyendo actas de partido (" + done.incrementAndGet() + "/" + toRead.size() + ")");
                return html;
            })));

            for (Competition c : comps) {
                for (TeamRef t : ClubTeams.own(c, club)) {
                    List<Match> matches = teamMatches(calendar.get(c), t.teamId());
                    Map<Integer, List<LineupPlayer>> lineups = new LinkedHashMap<>();
                    Map<Integer, List<MatchEvent>> events = new LinkedHashMap<>();
                    for (Match m : matches) {
                        String html = ClubTeams.await(sheets.get(m.idp()));
                        lineups.put(m.idp(), RosterService.ownSide(SidgadParser.lineups(html), m, Set.of(t.teamId())));
                        events.put(m.idp(), SidgadParser.gameEvents(html));
                    }
                    inputs.add(new TeamInput(c, ClubTeams.label(c, club, keys.getOrDefault(t.teamId(), club.code())),
                            t.teamId(), matches, lineups, events));
                }
            }
        }
        inputs = withSummaries(q, comps, inputs, job);
        ReportData.ClubInfo info = new ReportData.ClubInfo(club.id(), club.code(), club.name(),
                Crests.tile(sidgad.clubLogo(ReportService.logoFile(all, club.id()))));
        // Su categoría real es la más baja en que jugó; el informe se queda con las categorías que le corresponden.
        Query resolved = baseLevel(q.federation(), q.playerId(), inputs).map(q::withBase).orElse(q);
        List<TeamInput> own = inputs.stream().filter(in -> resolved.eligible(in.competition())).toList();
        return assemble(resolved, info, LocalDate.now(clock), q.federation().levels(), own)
                .withLeagueRanks(leagueRanks(q.playerId(), own, scorerTables));
    }

    /**
     * Su lugar en la tabla de goleadores de cada categoría: suma los goles de cada jugador en los torneos de esa
     * categoría (Apertura y Clausura) y lo ubica entre todos. Sin goles, o si una tabla no se pudo leer, esa categoría
     * queda sin lugar en la liga.
     */
    static Map<String, PlayerReport.LeagueRank> leagueRanks(int playerId, List<TeamInput> inputs,
                                                            Map<Competition, Future<String>> scorerTables) {
        Map<String, Set<Competition>> byCategory = new LinkedHashMap<>();
        inputs.forEach(in -> byCategory.computeIfAbsent(in.category(), k -> new LinkedHashSet<>()).add(in.competition()));
        Map<String, PlayerReport.LeagueRank> ranks = new LinkedHashMap<>();
        byCategory.forEach((category, comps) -> {
            List<List<PlayerStats>> tables = new ArrayList<>();
            for (Competition c : comps) {
                Future<String> f = scorerTables.get(c);
                try {
                    tables.add(f == null ? List.of() : SidgadParser.playerStats(ClubTeams.await(f)));
                } catch (RuntimeException e) {
                    log.info("No se pudo leer la tabla de goleadores de {}: {}", c.id(), e.getMessage());
                    return;
                }
            }
            PlayerReport.LeagueRank rank = leagueRank(playerId, tables);
            if (rank != null) {
                ranks.put(category, rank);
            }
        });
        return ranks;
    }

    /**
     * Cuándo llegaron sus goles en los partidos de unos equipos: abrió el marcador, empató, puso en ventaja o dio el
     * triunfo. Solo las actas completas (sus goles suman el resultado), así el orden de los goles es el real; dos goles
     * en el mismo segundo van en el orden del acta.
     */
    static PlayerReport.GoalMoments goalMoments(int playerId, List<TeamInput> inputs) {
        PlayerReport.GoalMoments total = PlayerReport.GoalMoments.EMPTY;
        for (TeamInput in : inputs) {
            for (Match m : in.matches()) {
                List<MatchEvent> goals = in.events().getOrDefault(m.idp(), List.of()).stream()
                        .filter(e -> e.type() == EventType.GOAL && e.period() >= 1 && e.period() <= 2)
                        .sorted(Comparator.comparingInt(MatchEvent::period).thenComparingInt(MatchEvent::minute)
                                .thenComparingInt(MatchEvent::second))
                        .toList();
                if (!m.played() || goals.isEmpty() || goals.size() != m.homeGoals() + m.awayGoals()) {
                    continue;
                }
                boolean home = m.homeTeamId() == in.teamId();
                int goalsFor = home ? m.homeGoals() : m.awayGoals();
                int goalsAgainst = home ? m.awayGoals() : m.homeGoals();
                int own = 0;
                int rival = 0;
                int withMinute = 0, opener = 0, equalizer = 0, goAhead = 0, winner = 0, firstHalf = 0, secondHalf = 0;
                for (MatchEvent g : goals) {
                    boolean ours = g.teamId() == in.teamId();
                    int before = own - rival;
                    boolean first = own + rival == 0;
                    if (ours) {
                        own++;
                    } else {
                        rival++;
                    }
                    if (!ours || g.playerId() != playerId) {
                        continue;
                    }
                    withMinute++;
                    if (g.period() == 1) {
                        firstHalf++;
                    } else {
                        secondHalf++;
                    }
                    if (first) {
                        opener++;
                    } else if (before == -1) {
                        equalizer++;
                    } else if (before == 0) {
                        goAhead++;
                    }
                    // En un partido ganado, el gol propio número (goles del rival + 1) es el que lo dejó arriba para
                    // siempre.
                    if (goalsFor > goalsAgainst && own == goalsAgainst + 1) {
                        winner++;
                    }
                }
                total = total.plus(new PlayerReport.GoalMoments(withMinute, opener, equalizer, goAhead, winner,
                        firstHalf, secondHalf));
            }
        }
        return total;
    }

    /** Su puesto entre los goleadores de unas tablas de SIDGAD sumadas; null si no tiene goles en ellas. */
    static PlayerReport.LeagueRank leagueRank(int playerId, List<List<PlayerStats>> tables) {
        Map<Integer, Integer> goals = new HashMap<>();
        tables.forEach(t -> t.forEach(p -> goals.merge(p.playerId(), p.goals(), Integer::sum)));
        int mine = goals.getOrDefault(playerId, 0);
        if (mine == 0) {
            return null;
        }
        int position = 1 + (int) goals.values().stream().filter(g -> g > mine).count();
        int leader = goals.values().stream().mapToInt(Integer::intValue).max().orElse(mine);
        return new PlayerReport.LeagueRank(position, goals.size(), mine, leader);
    }

    /**
     * Partidos sin acta publicada: SIDGAD los lista igual en la ficha de temporada del jugador, con sus goles, libres,
     * penales, azules y rojas. Si la ficha no se puede leer, esos partidos quedan como "sin acta".
     */
    private List<TeamInput> withSummaries(Query q, List<Competition> comps, List<TeamInput> inputs, Job job) {
        boolean missing = inputs.stream().anyMatch(in -> in.matches().stream()
                .anyMatch(m -> in.sheets().getOrDefault(m.idp(), List.of()).isEmpty()));
        if (!missing) {
            return inputs;
        }
        job.update(Status.EXTRACTING, "Leyendo la ficha de temporada del jugador (partidos sin acta)");
        List<SeasonMatch> season = new ArrayList<>();
        for (int seasonId : comps.stream().map(Competition::seasonId).distinct().toList()) {
            try {
                season.addAll(SidgadParser.seasonMatches(sidgad.playerSeason(q.playerId(), seasonId)));
            } catch (SidgadClient.SidgadException e) {
                log.info("No se pudo leer la ficha de temporada del jugador {}: {}", q.playerId(), e.getMessage());
                return inputs;
            }
        }
        if (season.isEmpty()) {
            return inputs;
        }
        return inputs.stream().map(in -> in.withSummaries(summaries(in, season))).toList();
    }

    /**
     * Los partidos sin acta del equipo que figuran en la ficha de temporada del jugador: por idp o, si la fila no lo
     * trae, por fecha dentro de la misma competición.
     */
    static Map<Integer, SeasonMatch> summaries(TeamInput in, List<SeasonMatch> season) {
        Map<Integer, SeasonMatch> out = new LinkedHashMap<>();
        for (Match m : in.matches()) {
            if (!in.sheets().getOrDefault(m.idp(), List.of()).isEmpty()) {
                continue;
            }
            season.stream()
                    .filter(s -> s.idp() == m.idp() || (s.idp() == 0 && Objects.equals(s.date(), m.date())
                            && sameCompetition(s.competition(), in.competition().name())))
                    .findFirst()
                    .ifPresent(s -> out.put(m.idp(), s));
        }
        return out;
    }

    private static boolean sameCompetition(String a, String b) {
        String x = a.toUpperCase().replaceAll("\\s+", " ").trim();
        String y = b.toUpperCase().replaceAll("\\s+", " ").trim();
        // Igual, no "empieza con": "JUNIORS FEMENINO" y "JUNIORS FEMENINO 2" son competiciones distintas.
        return !x.isEmpty() && x.equals(y);
    }

    /** La categoría más baja en que figura el jugador en las planillas (su categoría real), si figura en alguna. */
    static Optional<String> baseLevel(int playerId, List<TeamInput> inputs) {
        return baseLevel(Federation.FPP, playerId, inputs);
    }

    static Optional<String> baseLevel(Federation fed, int playerId, List<TeamInput> inputs) {
        return Optional.ofNullable(Eligibility.lowest(fed, inputs.stream()
                .filter(in -> in.playedBy(playerId))
                .map(in -> in.competition().category().level())
                .toList()));
    }

    static List<Match> teamMatches(List<Match> calendar, int teamId) {
        return calendar.stream()
                .filter(m -> m.played() && m.idp() > 0 && (m.homeTeamId() == teamId || m.awayTeamId() == teamId))
                .toList();
    }

    /** Arma el informe con lo leído (sin I/O: se prueba con fixtures). */
    static PlayerReport assemble(Query q, ReportData.ClubInfo club, LocalDate today, List<String> levels,
                                 List<TeamInput> inputs) {
        Map<String, Line> lines = new LinkedHashMap<>();
        Map<String, Integer> order = new LinkedHashMap<>();
        List<MatchRow> rows = new ArrayList<>();
        // Tramos según la duración de tiempo más larga entre las categorías en que jugó (20' o 25').
        MinuteCounter minutes = new MinuteCounter(inputs.stream()
                .filter(in -> in.playedBy(q.playerId()))
                .mapToInt(in -> in.competition().periodMinutes() > 0 ? in.competition().periodMinutes()
                        : Competition.DEFAULT_PERIOD_MINUTES)
                .max().orElse(Competition.DEFAULT_PERIOD_MINUTES));
        ShortHandedCalc shortHanded = new ShortHandedCalc();
        // Lo mismo por categoría (cada una con la duración de sus tiempos).
        Map<String, MinuteCounter> minutesByCategory = new LinkedHashMap<>();
        Map<String, ShortHandedCalc> shortHandedByCategory = new LinkedHashMap<>();
        for (TeamInput in : inputs) {
            int period = in.competition().periodMinutes() > 0 ? in.competition().periodMinutes()
                    : Competition.DEFAULT_PERIOD_MINUTES;
            minutesByCategory.merge(in.category(), new MinuteCounter(period),
                    (a, b) -> a.periodMinutes() >= b.periodMinutes() ? a : b);
            shortHandedByCategory.putIfAbsent(in.category(), new ShortHandedCalc());
        }
        Map<String, Integer> fromSummary = new LinkedHashMap<>();
        for (TeamInput in : inputs) {
            int won = 0, drawn = 0, lost = 0, goals = 0, yellow = 0, blue = 0, red = 0, played = 0, withoutSheet = 0;
            int teamGoals = 0;   // goles de su equipo en los partidos que jugó
            int teamCards = 0, sheetCards = 0;   // tarjetas de su equipo y suyas, en los partidos con acta
            int penScored = 0, penTaken = 0, fdScored = 0, fdTaken = 0;
            for (Match m : in.matches()) {
                boolean home = m.homeTeamId() == in.teamId();
                List<LineupPlayer> sheet = in.sheets().getOrDefault(m.idp(), List.of());
                SeasonMatch summary = sheet.isEmpty() ? in.summaries().get(m.idp()) : null;
                if (sheet.isEmpty() && summary == null) {
                    if (!in.summaryLoaded()) {
                        withoutSheet++; // acta no publicada y sin ficha de temporada: no se sabe si jugó
                    }
                    continue;           // sin acta y no figura en su ficha de temporada: no jugó
                }
                Optional<LineupPlayer> me = summary != null ? Optional.of(fromSummary(q, summary, home))
                        : sheet.stream().filter(p -> p.playerId() == q.playerId()).findFirst();
                if (me.isEmpty()) {
                    continue;           // no figura en la planilla de ese partido
                }
                if (summary != null) {
                    fromSummary.merge(in.category(), 1, Integer::sum);
                }
                int gf = home ? m.homeGoals() : m.awayGoals();
                int ga = home ? m.awayGoals() : m.homeGoals();
                String result = gf > ga ? "G" : gf == ga ? "E" : "P";
                played++;
                teamGoals += gf;
                switch (result) {
                    case "G" -> won++;
                    case "E" -> drawn++;
                    default -> lost++;
                }
                LineupPlayer p = me.get();
                if (summary == null) {
                    teamCards += sheet.stream().mapToInt(x -> x.yellowCards() + x.blueCards() + x.redCards()).sum();
                    sheetCards += p.yellowCards() + p.blueCards() + p.redCards();
                }
                goals += p.goals();
                yellow += p.yellowCards();
                blue += p.blueCards();
                red += p.redCards();
                penScored += p.penaltiesScored();
                penTaken += p.penaltiesTaken();
                fdScored += p.directFreeScored();
                fdTaken += p.directFreeTaken();
                List<MatchEvent> matchEvents = in.events().getOrDefault(m.idp(), List.of());
                List<MatchEvent> mine = matchEvents.stream().filter(e -> e.playerId() == q.playerId()).toList();
                minutes.add(p, mine);
                minutesByCategory.get(in.category()).add(p, mine);
                addShortHanded(shortHanded, q, in, m, home, matchEvents, gf, ga);
                addShortHanded(shortHandedByCategory.get(in.category()), q, in, m, home, matchEvents, gf, ga);
                rows.add(new MatchRow(m.date(), in.category(),
                        in.competition().tournament() == null ? "" : in.competition().tournament().label(),
                        home ? m.awayName() : m.homeName(), home, gf, ga, result,
                        p.starter(), p.goals(), p.yellowCards(), p.blueCards(), p.redCards(), summary != null));
            }
            if (played == 0) {
                continue;               // el jugador no figura en este equipo
            }
            Line line = new Line(in.category(), played, won, drawn, lost, goals, yellow, blue, red,
                    penScored, penTaken, fdScored, fdTaken, withoutSheet, teamGoals, teamCards, sheetCards);
            lines.merge(in.category(), line, (a, b) -> a.plus(b, a.category()));
            order.putIfAbsent(in.category(), levels.indexOf(in.competition().category().level()));
        }
        List<Line> categories = lines.values().stream()
                .sorted(Comparator.comparing((Line l) -> order.get(l.category())).thenComparing(Line::category))
                .toList();
        Line total = categories.stream().reduce(Line.EMPTY, (a, b) -> a.plus(b, "Total"));
        rows.sort(Comparator.comparing(MatchRow::date, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> notes = new ArrayList<>();
        if (q.base() != null && q.eligibleLabel() != null) {
            notes.add("Su categoría es " + new Competition.Category(q.base(), "").levelLabel() + " (la más baja en que jugó en "
                    + q.year() + "). Categorías en que puede jugar: " + q.eligibleLabel() + ".");
        } else if (q.eligibleLabel() != null) {
            notes.add("Categorías revisadas: " + q.eligibleLabel() + ", las que corresponden a su categoría.");
        }
        if (categories.isEmpty()) {
            notes.add(q.playerName() + " no figura en las actas publicadas de " + club.name() + " en " + q.year()
                    + " (" + q.scope().label() + ").");
        }
        for (Line l : categories) {
            int summarized = fromSummary.getOrDefault(l.category(), 0);
            if (summarized > 0) {
                notes.add(l.category() + ": " + (summarized == 1 ? "1 partido sin acta publicada se tomó"
                        : summarized + " partidos sin acta publicada se tomaron") + " de su ficha de temporada en"
                        + " SIDGAD (goles, azules, rojas, penales y libres; sin amarillas ni minutos). Van marcados"
                        + " con * en el partido a partido.");
            }
            if (l.withoutSheet() > 0) {
                notes.add(l.category() + ": " + l.withoutSheet() + (l.withoutSheet() == 1
                        ? " partido del equipo no tiene acta publicada; si jugó, no está contado."
                        : " partidos del equipo no tienen acta publicada; si jugó, no están contados."));
            }
        }
        List<PlayerReport.CategoryBlock> blocks = categories.stream()
                .map(l -> {
                    ReportData.ShortHanded catShort = shortHandedByCategory.get(l.category()).result();
                    List<TeamInput> catInputs = inputs.stream().filter(in -> in.category().equals(l.category())).toList();
                    return new PlayerReport.CategoryBlock(l, minutesByCategory.get(l.category()).result(), catShort,
                            rows.stream().filter(row -> row.category().equals(l.category())).toList(),
                            PlayerStrengths.evaluate(q.playerId(), catInputs, catShort), goalMoments(q.playerId(), catInputs));
                })
                .toList();
        ReportData.ShortHanded sh = shortHanded.result();
        return new PlayerReport(q.playerId(), q.playerName(), q.fullName(), club, q.year(), q.scope().label(), today,
                categories, total, minutes.result(), sh, rows, notes, blocks,
                PlayerStrengths.evaluate(q.playerId(), inputs, sh), q.federation());
    }

    /**
     * Su "planilla" en un partido sin acta, armada con la ficha de temporada: sin amarillas ni titularidad (la ficha
     * no las trae). Sin eventos, sus goles y tarjetas van a "sin minuto registrado".
     */
    static LineupPlayer fromSummary(Query q, SeasonMatch s, boolean home) {
        return new LineupPlayer(q.playerId(), "", home, "", q.playerName(), false, s.goals(), 0, s.blueCards(),
                s.redCards(), s.penaltiesScored(), s.penaltiesTaken(), s.directFreeScored(), s.directFreeTaken());
    }

    /**
     * Sanciones del jugador (azul 2', roja 4') en el partido y goles de cada equipo mientras las cumplía. Solo con
     * los eventos del acta: sin eventos no se conoce el minuto, y el partido no se cuenta.
     */
    private static void addShortHanded(ShortHandedCalc calc, Query q, TeamInput in, Match m, boolean home,
                                       List<MatchEvent> events, int gf, int ga) {
        if (events.isEmpty()) {
            return;
        }
        int period = in.competition().periodMinutes() > 0 ? in.competition().periodMinutes()
                : Competition.DEFAULT_PERIOD_MINUTES;
        List<int[]> sanctions = new ArrayList<>();
        List<String> sanctionCards = new ArrayList<>();
        List<Integer> own = new ArrayList<>();
        List<Integer> opponent = new ArrayList<>();
        int blues = 0;
        int reds = 0;
        for (MatchEvent e : events) {
            int p = e.period() == HALFTIME_PERIOD ? 2 : e.period();
            if (p < 1 || p > 2) {
                continue;
            }
            int t = (p - 1) * period * 60 + (e.period() == HALFTIME_PERIOD ? 0 : e.minute() * 60 + e.second());
            if (e.type() == EventType.GOAL) {
                boolean ours = e.teamId() > 0 ? e.teamId() == in.teamId() : e.clubId() == q.clubId();
                (ours ? own : opponent).add(t);
            } else if (e.playerId() == q.playerId()
                    && (e.type() == EventType.BLUE_CARD || e.type() == EventType.RED_CARD)) {
                boolean blue = e.type() == EventType.BLUE_CARD;
                sanctions.add(new int[]{t, t + (blue ? 120 : 240)});
                sanctionCards.add(blue ? "Azul" : "Roja");
                blues += blue ? 1 : 0;
                reds += blue ? 0 : 1;
            }
        }
        String rivalName = home ? m.awayName() : m.homeName();
        int end = 2 * period * 60;
        calc.add(m.date(), rivalName, home, sanctions, blues, reds, own, opponent, end, gf, ga);
        // Cada gol en contra que cae dentro de una de sus sanciones (se cumple completa aunque haya gol).
        for (int t : opponent) {
            for (int i = 0; i < sanctions.size(); i++) {
                int[] s = sanctions.get(i);
                if (t >= s[0] && t < Math.min(s[1], end)) {
                    calc.conceded(new ReportData.ConcededGoal(m.date(), rivalName, sanctionCards.get(i),
                            clock(s[0], period), clock(t, period), gf, ga));
                    break;
                }
            }
        }
    }

    /** "1T 12:30": tiempo y minuto dentro del tiempo, a partir de los segundos desde el inicio del partido. */
    static String clock(int seconds, int periodMinutes) {
        int half = seconds < periodMinutes * 60 ? 1 : 2;
        int rel = seconds - (half - 1) * periodMinutes * 60;
        return String.format(java.util.Locale.ROOT, "%dT %d:%02d", half, rel / 60, rel % 60);
    }

    /**
     * Cuenta goles y tarjetas del jugador por tramo de 5 minutos con los eventos del acta. Lo que la planilla le anota
     * y no tiene evento con minuto (o es de fuera del juego, período 11) va a "sin minuto registrado".
     */
    static final class MinuteCounter {
        private final int periodMinutes;
        private final int perPeriod;
        private final int[][] counts;
        private final int[] unrecorded = new int[4];

        int periodMinutes() {
            return periodMinutes;
        }

        MinuteCounter(int periodMinutes) {
            this.periodMinutes = periodMinutes;
            this.perPeriod = (periodMinutes + BUCKET_MINUTES - 1) / BUCKET_MINUTES;
            this.counts = new int[2 * perPeriod][4];
        }

        void add(LineupPlayer sheet, List<MatchEvent> events) {
            int[] withMinute = new int[4];
            for (MatchEvent e : events) {
                int column = switch (e.type()) {
                    case GOAL -> 0;
                    case YELLOW_CARD -> 1;
                    case BLUE_CARD -> 2;
                    case RED_CARD -> 3;
                    case FOUL -> -1;
                };
                if (column < 0) {
                    continue;
                }
                int period = e.period() == HALFTIME_PERIOD ? 2 : e.period();
                int minute = e.period() == HALFTIME_PERIOD ? 0 : e.minute();
                if (period < 1 || period > 2) {
                    continue;   // fuera del juego: queda como "sin minuto"
                }
                counts[(period - 1) * perPeriod + Math.min(minute / BUCKET_MINUTES, perPeriod - 1)][column]++;
                withMinute[column]++;
            }
            int[] inSheet = {sheet.goals(), sheet.yellowCards(), sheet.blueCards(), sheet.redCards()};
            for (int c = 0; c < 4; c++) {
                unrecorded[c] += Math.max(0, inSheet[c] - withMinute[c]);
            }
        }

        PlayerReport.Minutes result() {
            List<PlayerReport.Bucket> buckets = new ArrayList<>();
            for (int i = 0; i < counts.length; i++) {
                int from = (i % perPeriod) * BUCKET_MINUTES;
                int to = Math.min(from + BUCKET_MINUTES, periodMinutes);
                String label = (i < perPeriod ? "1T " : "2T ") + from + "-" + to + "'";
                buckets.add(new PlayerReport.Bucket(label, counts[i][0], counts[i][1], counts[i][2], counts[i][3]));
            }
            return new PlayerReport.Minutes(periodMinutes, buckets, new PlayerReport.Bucket("Sin minuto registrado",
                    unrecorded[0], unrecorded[1], unrecorded[2], unrecorded[3]));
        }
    }

    static String slug(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
