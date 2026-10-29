package com.madsports.player;

import com.madsports.player.PlayerReport.Line;
import com.madsports.player.PlayerReport.MatchRow;
import com.madsports.player.PlayerReportService.Query;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.SeasonMatch;
import com.madsports.stats.ReportData;
import com.madsports.stats.ShortHandedCalc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Arma el informe de un jugador con lo ya leído de SIDGAD (sin I/O: se prueba con fixtures): sus líneas por
 * categoría y torneo, el partido a partido, los minutos de sus goles y tarjetas, las sanciones y las notas.
 * {@link PlayerReportService} lee de SIDGAD y le pasa lo leído.
 */
final class PlayerReportAssembler {

    /** Período 10 de SIDGAD = descanso: cuenta como inicio del 2do tiempo. */
    static final int HALFTIME_PERIOD = 10;

    private PlayerReportAssembler() {
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
        Map<String, PlayerReport.TournamentLine> tournamentLines = new LinkedHashMap<>();
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
            String tournament = in.competition().tournament() == null ? "" : in.competition().tournament().label();
            tournamentLines.merge(in.category() + "|" + tournament,
                    new PlayerReport.TournamentLine(in.category(), tournament, line), PlayerReport.TournamentLine::plus);
            order.putIfAbsent(in.category(), levels.indexOf(in.competition().category().level()));
        }
        List<Line> categories = lines.values().stream()
                .sorted(Comparator.comparing((Line l) -> order.get(l.category())).thenComparing(Line::category))
                .toList();
        Line total = categories.stream().reduce(Line.EMPTY, (a, b) -> a.plus(b, "Total"));
        rows.sort(Comparator.comparing(MatchRow::date, Comparator.nullsLast(Comparator.naturalOrder())));
        // Por torneo: en el orden de las categorías y, dentro de cada una, por la fecha de su primer partido.
        Map<String, LocalDate> firstDate = new LinkedHashMap<>();
        rows.forEach(r -> firstDate.putIfAbsent(r.category() + "|" + r.tournament(), r.date()));
        List<PlayerReport.TournamentLine> tournaments = tournamentLines.values().stream()
                .sorted(Comparator.comparing((PlayerReport.TournamentLine t) -> order.get(t.category()))
                        .thenComparing(PlayerReport.TournamentLine::category)
                        .thenComparing(t -> firstDate.get(t.category() + "|" + t.tournament()),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

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
                PlayerStrengths.evaluate(q.playerId(), inputs, sh), q.federation(), tournaments);
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
}
