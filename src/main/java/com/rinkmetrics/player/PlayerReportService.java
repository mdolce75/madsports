package com.rinkmetrics.player;

import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.config.FppProperties;
import com.rinkmetrics.player.PlayerReport.Line;
import com.rinkmetrics.player.PlayerReport.MatchRow;
import com.rinkmetrics.report.Crests;
import com.rinkmetrics.report.PdfRenderer;
import com.rinkmetrics.report.ReportService;
import com.rinkmetrics.report.ReportService.Scope;
import com.rinkmetrics.report.ReportService.Status;
import com.rinkmetrics.sidgad.SidgadClient;
import com.rinkmetrics.sidgad.model.Competition;
import com.rinkmetrics.sidgad.model.Models.LineupPlayer;
import com.rinkmetrics.sidgad.model.Models.Match;
import com.rinkmetrics.sidgad.model.Models.MatchEvent;
import com.rinkmetrics.sidgad.model.Models.EventType;
import com.rinkmetrics.stats.ShortHandedCalc;
import com.rinkmetrics.sidgad.model.Models.TeamRef;
import com.rinkmetrics.sidgad.parse.SidgadParser;
import com.rinkmetrics.stats.ReportData;
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
import java.util.LinkedHashMap;
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
     * @param levels     categorías en que puede jugar (ver {@link Eligibility}); vacía = todas
     * @param branch     rama del jugador (MASCULINO o FEMENINO); null = ambas
     */
    public record Query(int playerId, String playerName, String fullName, int clubId, int year, Scope scope,
                        List<String> levels, String branch) {

        public Query(int playerId, String playerName, String fullName, int clubId, int year, Scope scope) {
            this(playerId, playerName, fullName, clubId, year, scope, List.of(), null);
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
        private volatile Status status = Status.PENDIENTE;
        private volatile String message = "En cola";
        private volatile byte[] pdf;
        private volatile String fileName;

        Job(String owner) {
            this.owner = owner;
        }

        public UUID id() { return id; }
        public Status status() { return status; }
        public String message() { return message; }
        public byte[] pdf() { return pdf; }
        public String fileName() { return fileName; }

        void update(Status status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    /**
     * Lo leído de SIDGAD para un equipo del club en una competición: sus partidos jugados, su planilla en cada uno y
     * los eventos del acta (goles y tarjetas con minuto e {@code id_player}).
     */
    record TeamInput(Competition competition, String category, int teamId, List<Match> matches,
                     Map<Integer, List<LineupPlayer>> sheets, Map<Integer, List<MatchEvent>> events) {

        TeamInput(Competition competition, String category, int teamId, List<Match> matches,
                  Map<Integer, List<LineupPlayer>> sheets) {
            this(competition, category, teamId, matches, sheets, Map.of());
        }
    }

    static final int BUCKET_MINUTES = 5;
    /** Período 10 de SIDGAD = descanso: cuenta como inicio del 2do tiempo. */
    static final int HALFTIME_PERIOD = 10;

    private final CatalogService catalog;
    private final SidgadClient sidgad;
    private final PdfRenderer renderer;
    private final Clock clock;
    private final List<String> levels;
    private final Cache<UUID, Job> jobs = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public PlayerReportService(CatalogService catalog, SidgadClient sidgad, PdfRenderer renderer, Clock clock,
                               FppProperties props) {
        this.catalog = catalog;
        this.sidgad = sidgad;
        this.renderer = renderer;
        this.clock = clock;
        this.levels = props.sidgad().levels();
    }

    public Job submit(Query query, String owner) {
        if (query.scope() == Scope.ULTIMOS_5) {
            throw new IllegalArgumentException("Para jugadores el período es Apertura, Clausura o Apertura + Clausura.");
        }
        if (!catalog.available(query.year())) {
            throw new IllegalArgumentException(CatalogService.unavailableMessage(query.year()));
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
            job.update(Status.GENERANDO_PDF, "Generando el PDF");
            job.pdf = renderer.playerPdf(report);
            job.fileName = "informe-jugador-" + slug(q.playerName()) + "-" + q.year() + "-"
                    + q.scope().name().toLowerCase() + ".pdf";
            job.update(Status.LISTO, "Informe listo");
        } catch (RuntimeException e) {
            log.error("Falló el informe de jugador {}", job.id(), e);
            job.update(Status.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    PlayerReport build(Query q, Job job) {
        ClubView club = catalog.club(q.clubId(), q.year());
        List<Competition> all = catalog.competitions(q.year()).stream()
                .filter(c -> !ClubTeams.own(c, club).isEmpty())
                .toList();
        // Las letras A/B se deciden con todo el año (como en el informe de club); después se filtra el período.
        Map<Integer, String> keys = ClubTeams.keys(all, club);
        // Solo las categorías en que puede jugar (puede subir de categoría, nunca bajar) y su rama.
        List<Competition> comps = all.stream()
                .filter(c -> q.scope().torneo() == null || c.torneo() == q.scope().torneo())
                .filter(q::eligible)
                .toList();
        List<TeamInput> inputs = new ArrayList<>();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            job.update(Status.EXTRAYENDO, "Leyendo los calendarios de los equipos del club");
            Map<Competition, Future<String>> calendars = new LinkedHashMap<>();
            comps.forEach(c -> calendars.put(c, io.submit(() -> sidgad.calendar(c.id()))));
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
            job.update(Status.EXTRAYENDO, "Leyendo actas de partido (0/" + toRead.size() + ")");
            Map<Integer, Future<String>> sheets = new LinkedHashMap<>();
            toRead.forEach((idp, c) -> sheets.put(idp, io.submit(() -> {
                String html = sidgad.gameSheet(c.id(), idp);
                job.update(Status.EXTRAYENDO, "Leyendo actas de partido (" + done.incrementAndGet() + "/" + toRead.size() + ")");
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
        ReportData.ClubInfo info = new ReportData.ClubInfo(club.id(), club.code(), club.name(),
                Crests.tile(sidgad.clubLogo(ReportService.logoFile(all, club.id()))));
        return assemble(q, info, LocalDate.now(clock), levels, inputs);
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
                .filter(in -> in.sheets().values().stream()
                        .anyMatch(sheet -> sheet.stream().anyMatch(p -> p.playerId() == q.playerId())))
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
        for (TeamInput in : inputs) {
            int won = 0, drawn = 0, lost = 0, goals = 0, yellow = 0, blue = 0, red = 0, played = 0, withoutSheet = 0;
            int penScored = 0, penTaken = 0, fdScored = 0, fdTaken = 0;
            for (Match m : in.matches()) {
                List<LineupPlayer> sheet = in.sheets().getOrDefault(m.idp(), List.of());
                if (sheet.isEmpty()) {
                    withoutSheet++;     // acta no publicada: no se sabe si jugó
                    continue;
                }
                Optional<LineupPlayer> me = sheet.stream().filter(p -> p.playerId() == q.playerId()).findFirst();
                if (me.isEmpty()) {
                    continue;           // no figura en la planilla de ese partido
                }
                boolean home = m.homeTeamId() == in.teamId();
                int gf = home ? m.homeGoals() : m.awayGoals();
                int ga = home ? m.awayGoals() : m.homeGoals();
                String result = gf > ga ? "G" : gf == ga ? "E" : "P";
                played++;
                switch (result) {
                    case "G" -> won++;
                    case "E" -> drawn++;
                    default -> lost++;
                }
                LineupPlayer p = me.get();
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
                        in.competition().torneo() == null ? "" : in.competition().torneo().label(),
                        home ? m.awayName() : m.homeName(), home, gf, ga, result,
                        p.starter(), p.goals(), p.yellowCards(), p.blueCards(), p.redCards()));
            }
            if (played == 0) {
                continue;               // el jugador no figura en este equipo
            }
            Line line = new Line(in.category(), played, won, drawn, lost, goals, yellow, blue, red,
                    penScored, penTaken, fdScored, fdTaken, withoutSheet);
            lines.merge(in.category(), line, (a, b) -> a.plus(b, a.category()));
            order.putIfAbsent(in.category(), levels.indexOf(in.competition().category().level()));
        }
        List<Line> categories = lines.values().stream()
                .sorted(Comparator.comparing((Line l) -> order.get(l.category())).thenComparing(Line::category))
                .toList();
        Line total = categories.stream().reduce(Line.EMPTY, (a, b) -> a.plus(b, "Total"));
        rows.sort(Comparator.comparing(MatchRow::date, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> notes = new ArrayList<>();
        if (q.eligibleLabel() != null) {
            notes.add("Categorías revisadas: " + q.eligibleLabel() + ", las que corresponden a su categoría.");
        }
        if (categories.isEmpty()) {
            notes.add(q.playerName() + " no figura en las actas publicadas de " + club.name() + " en " + q.year()
                    + " (" + q.scope().label() + ").");
        }
        for (Line l : categories) {
            if (l.withoutSheet() > 0) {
                notes.add(l.category() + ": " + l.withoutSheet() + (l.withoutSheet() == 1
                        ? " partido del equipo no tiene acta publicada; si jugó, no está contado."
                        : " partidos del equipo no tienen acta publicada; si jugó, no están contados."));
            }
        }
        List<PlayerReport.CategoryBlock> blocks = categories.stream()
                .map(l -> new PlayerReport.CategoryBlock(l, minutesByCategory.get(l.category()).result(),
                        shortHandedByCategory.get(l.category()).result(),
                        rows.stream().filter(row -> row.category().equals(l.category())).toList()))
                .toList();
        return new PlayerReport(q.playerId(), q.playerName(), q.fullName(), club, q.year(), q.scope().label(), today,
                categories, total, minutes.result(), shortHanded.result(), rows, notes, blocks);
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
        List<Integer> own = new ArrayList<>();
        List<Integer> rival = new ArrayList<>();
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
                (ours ? own : rival).add(t);
            } else if (e.playerId() == q.playerId()
                    && (e.type() == EventType.BLUE_CARD || e.type() == EventType.RED_CARD)) {
                boolean blue = e.type() == EventType.BLUE_CARD;
                sanctions.add(new int[]{t, t + (blue ? 120 : 240)});
                blues += blue ? 1 : 0;
                reds += blue ? 0 : 1;
            }
        }
        calc.add(m.date(), home ? m.awayName() : m.homeName(), home, sanctions, blues, reds, own, rival,
                2 * period * 60, gf, ga);
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
