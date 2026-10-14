package com.fppstats.player;

import com.fppstats.catalog.CatalogService;
import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.config.FppProperties;
import com.fppstats.player.PlayerReport.Line;
import com.fppstats.player.PlayerReport.MatchRow;
import com.fppstats.report.Crests;
import com.fppstats.report.PdfRenderer;
import com.fppstats.report.ReportService;
import com.fppstats.report.ReportService.Scope;
import com.fppstats.report.ReportService.Status;
import com.fppstats.sidgad.SidgadClient;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Models.LineupPlayer;
import com.fppstats.sidgad.model.Models.Match;
import com.fppstats.sidgad.model.Models.TeamRef;
import com.fppstats.sidgad.parse.SidgadParser;
import com.fppstats.stats.ReportData;
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
     */
    public record Query(int playerId, String playerName, String fullName, int clubId, int year, Scope scope) {
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

    /** Lo leído de SIDGAD para un equipo del club en una competición: sus partidos jugados y su planilla en cada uno. */
    record TeamInput(Competition competition, String category, int teamId, List<Match> matches,
                     Map<Integer, List<LineupPlayer>> sheets) {
    }

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
        List<Competition> comps = all.stream()
                .filter(c -> q.scope().torneo() == null || c.torneo() == q.scope().torneo())
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
                    for (Match m : matches) {
                        lineups.put(m.idp(), RosterService.ownSide(
                                SidgadParser.lineups(ClubTeams.await(sheets.get(m.idp()))), m, Set.of(t.teamId())));
                    }
                    inputs.add(new TeamInput(c, ClubTeams.label(c, club, keys.getOrDefault(t.teamId(), club.code())),
                            t.teamId(), matches, lineups));
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
        for (TeamInput in : inputs) {
            int won = 0, drawn = 0, lost = 0, goals = 0, yellow = 0, blue = 0, red = 0, played = 0, withoutSheet = 0;
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
                rows.add(new MatchRow(m.date(), in.category(),
                        in.competition().torneo() == null ? "" : in.competition().torneo().label(),
                        home ? m.awayName() : m.homeName(), home, gf, ga, result,
                        p.starter(), p.goals(), p.yellowCards(), p.blueCards(), p.redCards()));
            }
            if (played == 0) {
                continue;               // el jugador no figura en este equipo
            }
            Line line = new Line(in.category(), played, won, drawn, lost, goals, yellow, blue, red, withoutSheet);
            lines.merge(in.category(), line, (a, b) -> a.plus(b, a.category()));
            order.putIfAbsent(in.category(), levels.indexOf(in.competition().category().level()));
        }
        List<Line> categories = lines.values().stream()
                .sorted(Comparator.comparing((Line l) -> order.get(l.category())).thenComparing(Line::category))
                .toList();
        Line total = categories.stream().reduce(Line.EMPTY, (a, b) -> a.plus(b, "Total"));
        rows.sort(Comparator.comparing(MatchRow::date, Comparator.nullsLast(Comparator.naturalOrder())));

        List<String> notes = new ArrayList<>();
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
        return new PlayerReport(q.playerId(), q.playerName(), q.fullName(), club, q.year(), q.scope().label(), today,
                categories, total, rows, notes);
    }

    static String slug(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
