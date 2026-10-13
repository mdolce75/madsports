package com.fppstats.stats;

import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Models.EventType;
import com.fppstats.sidgad.model.Models.Match;
import com.fppstats.sidgad.model.Models.MatchEvent;
import com.fppstats.sidgad.model.Models.PlayerStats;
import com.fppstats.sidgad.model.Models.StandingRow;
import com.fppstats.sidgad.model.Models.TeamRef;
import com.fppstats.stats.ReportData.Discipline;
import com.fppstats.stats.ReportData.LeagueContext;
import com.fppstats.stats.ReportData.MatchLine;
import com.fppstats.stats.ReportData.PlayerLine;
import com.fppstats.stats.ReportData.TeamRecord;
import com.fppstats.stats.ReportData.RivalLine;
import com.fppstats.stats.ReportData.SetPieces;
import com.fppstats.stats.ReportData.Source;
import com.fppstats.stats.ReportData.StandingSnapshot;
import com.fppstats.stats.ReportData.TeamReport;
import com.fppstats.stats.ReportData.TimeBucket;
import com.fppstats.stats.ReportData.TimeProfile;
import com.fppstats.stats.ReportData.TorneoLine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Calcula las estadísticas predefinidas a partir de los datos extraídos. Sin I/O: fácil de testear. */
public final class StatsEngine {

    /**
     * Datos ya extraídos de una competición.
     *
     * @param standingsDate fecha de generación de la tabla en SIDGAD (puede quedar atrasada respecto del calendario)
     * @param sheets        eventos del acta por idp, para los partidos del club (lista vacía = acta no publicada)
     */
    public record CompetitionData(Competition competition, List<StandingRow> standings, LocalDate standingsDate,
                                  List<Match> matches, List<PlayerStats> players, Map<Integer, List<MatchEvent>> sheets) {
    }

    static final int BUCKET_MINUTES = 5;

    private static final Comparator<Match> CHRONO = Comparator
            .comparing(Match::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Match::round);

    private StatsEngine() {
    }

    public static ReportData build(ClubView club, String categoryLabel, String torneoLabel,
                                   List<CompetitionData> data, LocalDate today) {
        return build(club, categoryLabel, torneoLabel, data, today, 0);
    }

    /**
     * @param lastMatches si es mayor que 0, récord, resultados, rivales y perfil por minuto se calculan solo con
     *                    los últimos N partidos jugados de cada equipo (las estadísticas de jugadores siguen siendo
     *                    de los torneos completos: SIDGAD no las publica por partido)
     */
    public static ReportData build(ClubView club, String categoryLabel, String torneoLabel,
                                   List<CompetitionData> data, LocalDate today, int lastMatches) {
        // Un mismo equipo tiene distinto teamId en Apertura y Clausura: se agrupa por código.
        Map<String, List<Entry>> byTeam = new LinkedHashMap<>();
        for (CompetitionData cd : data) {
            List<TeamRef> own = cd.competition().teams().stream().filter(t -> t.clubId() == club.id()).toList();
            for (TeamRef t : own) {
                byTeam.computeIfAbsent(teamKey(t, own.size(), club.code()), k -> new ArrayList<>()).add(new Entry(cd, t));
            }
        }
        List<TeamReport> teams = byTeam.entrySet().stream()
                .map(e -> team(e.getKey(), e.getValue(), today, lastMatches)).toList();
        List<Source> sources = data.stream()
                .map(cd -> new Source(cd.competition().name(), cd.competition().url())).toList();
        return new ReportData(new ReportData.ClubInfo(club.id(), club.code(), club.name()),
                categoryLabel, torneoLabel, today, lastMatches, sources, teams, league(data), List.of());
    }

    /** Partidos jugados del equipo en todas sus competiciones, en orden cronológico; los últimos N si N > 0. */
    static Set<Match> includedMatches(List<Match> playedChronologically, int lastMatches) {
        int skip = lastMatches > 0 ? Math.max(0, playedChronologically.size() - lastMatches) : 0;
        return new HashSet<>(playedChronologically.subList(skip, playedChronologically.size()));
    }

    private record Entry(CompetitionData data, TeamRef team) {
    }

    /**
     * Identifica al equipo a lo largo de la temporada (el teamId cambia entre torneos). Un club puede tener
     * equipo A y B en la misma categoría y rama: "CAVS B" se mantiene, "GEBAB" (SIDGAD a veces omite el espacio)
     * pasa a "GEBA B", y si hay varios equipos el que no tiene letra es el A. Un único equipo queda con el código base.
     */
    static String teamKey(TeamRef t, int clubTeamsInCompetition, String clubCode) {
        String code = t.code().trim().replaceAll("\\s+", " ");
        if (code.matches(".*\\s[A-D]$")) {
            return code;
        }
        if (code.length() == clubCode.length() + 1 && code.startsWith(clubCode) && code.matches(".*[A-D]$")) {
            return clubCode + " " + code.charAt(code.length() - 1);
        }
        return clubTeamsInCompetition > 1 ? code + " A" : code;
    }

    private static TeamReport team(String key, List<Entry> entries, LocalDate today, int lastMatches) {
        String name = entries.getLast().team().name();
        List<Match> allPlayed = entries.stream()
                .flatMap(e -> e.data().matches().stream().filter(m -> m.played() && m.involves(e.team().teamId())))
                .sorted(CHRONO).toList();
        Set<Match> included = includedMatches(allPlayed, lastMatches);
        List<StandingSnapshot> standings = new ArrayList<>();
        List<MatchLine> results = new ArrayList<>();
        List<MatchLine> upcoming = new ArrayList<>();
        List<TorneoLine> byTorneo = new ArrayList<>();
        Map<Integer, List<PlayerStats>> playersById = new LinkedHashMap<>();
        TeamRecord total = TeamRecord.EMPTY;
        TeamRecord home = TeamRecord.EMPTY;
        TeamRecord away = TeamRecord.EMPTY;

        for (Entry e : entries) {
            Competition comp = e.data().competition();
            int teamId = e.team().teamId();
            TeamRecord compRecord = TeamRecord.EMPTY;
            int playedInCalendar = 0;
            for (Match m : e.data().matches().stream().filter(m -> m.involves(teamId)).sorted(CHRONO).toList()) {
                MatchLine line = line(comp, m, teamId);
                if (m.played()) {
                    playedInCalendar++;
                    if (!included.contains(m)) {
                        continue;
                    }
                    results.add(line);
                    compRecord = compRecord.plus(line.goalsFor(), line.goalsAgainst());
                    total = total.plus(line.goalsFor(), line.goalsAgainst());
                    if (line.home()) {
                        home = home.plus(line.goalsFor(), line.goalsAgainst());
                    } else {
                        away = away.plus(line.goalsFor(), line.goalsAgainst());
                    }
                } else if (m.date() == null || !m.date().isBefore(today)) {
                    upcoming.add(line);
                }
            }
            if (compRecord.played() > 0 || lastMatches == 0) {
                byTorneo.add(new TorneoLine(comp.torneoLabel(), compRecord));
            }
            snapshot(e.data(), e.team(), playedInCalendar).ifPresent(standings::add);
            e.data().players().stream().filter(p -> p.teamId() == teamId)
                    .forEach(p -> playersById.computeIfAbsent(p.playerId(), k -> new ArrayList<>()).add(p));
        }

        Comparator<MatchLine> byDate = Comparator.comparing(MatchLine::date, Comparator.nullsLast(Comparator.naturalOrder()));
        results.sort(byDate);
        upcoming.sort(byDate);
        List<String> form = results.stream().skip(Math.max(0, results.size() - 5)).map(MatchLine::outcome).toList();
        MatchLine biggestWin = results.stream().filter(r -> r.goalsFor() > r.goalsAgainst())
                .max(Comparator.comparingInt((MatchLine r) -> r.goalsFor() - r.goalsAgainst())).orElse(null);
        MatchLine worstLoss = results.stream().filter(r -> r.goalsFor() < r.goalsAgainst())
                .max(Comparator.comparingInt((MatchLine r) -> r.goalsAgainst() - r.goalsFor())).orElse(null);

        // Jugadores, pelota parada y disciplina vienen de las estadísticas del torneo completo (SIDGAD no las da
        // por partido): sus porcentajes y promedios se calculan contra todos los partidos jugados, no contra el recorte.
        int seasonPlayed = allPlayed.size();
        int seasonGoals = allPlayed.stream().mapToInt(m -> ownGoals(m, entries)).sum();
        List<PlayerLine> players = players(playersById, seasonGoals);
        SetPieces setPieces = new SetPieces(
                players.stream().mapToInt(PlayerLine::directFreeScored).sum(),
                players.stream().mapToInt(PlayerLine::directFreeTaken).sum(),
                players.stream().mapToInt(PlayerLine::penaltiesScored).sum(),
                players.stream().mapToInt(PlayerLine::penaltiesTaken).sum());
        int blue = players.stream().mapToInt(PlayerLine::blueCards).sum();
        int red = players.stream().mapToInt(PlayerLine::redCards).sum();
        Discipline discipline = new Discipline(blue, red, seasonPlayed == 0 ? 0 : (double) (blue + red) / seasonPlayed);

        return new TeamReport(key, name, standings, total, home, away, form, results, upcoming,
                biggestWin, worstLoss, players, setPieces, discipline, rivals(results), byTorneo, timeProfile(entries, included));
    }

    /** Goles del equipo del informe en un partido (el equipo es el de la competición a la que pertenece el partido). */
    private static int ownGoals(Match m, List<Entry> entries) {
        for (Entry e : entries) {
            if (m.homeTeamId() == e.team().teamId()) {
                return m.homeGoals();
            }
            if (m.awayTeamId() == e.team().teamId()) {
                return m.awayGoals();
            }
        }
        return 0;
    }

    /** Suma, por tramo de 5', goles y tarjetas propias y de los rivales en todos los partidos con acta. */
    private static TimeProfile timeProfile(List<Entry> entries, Set<Match> included) {
        int periodMinutes = entries.stream().mapToInt(e -> e.data().competition().periodMinutes()).max()
                .orElse(Competition.DEFAULT_PERIOD_MINUTES);
        int perPeriod = (periodMinutes + BUCKET_MINUTES - 1) / BUCKET_MINUTES;
        int[][] counts = new int[2 * perPeriod][8];
        int played = 0;
        int withSheet = 0;
        int mismatches = 0;

        for (Entry e : entries) {
            TeamRef own = e.team();
            for (Match m : e.data().matches()) {
                if (!m.involves(own.teamId()) || !included.contains(m)) {
                    continue;
                }
                played++;
                List<MatchEvent> events = e.data().sheets().getOrDefault(m.idp(), List.of());
                if (events.isEmpty()) {
                    continue;
                }
                withSheet++;
                boolean home = m.homeTeamId() == own.teamId();
                int rivalTeamId = home ? m.awayTeamId() : m.homeTeamId();
                int rivalClubId = home ? m.awayClubId() : m.homeClubId();
                int goalsInSheet = 0;
                for (MatchEvent ev : events) {
                    if (ev.period() < 1 || ev.period() > 2) {
                        continue;
                    }
                    boolean ours = isOwn(ev, own, rivalTeamId, rivalClubId);
                    int bucket = (ev.period() - 1) * perPeriod + Math.min(ev.minute() / BUCKET_MINUTES, perPeriod - 1);
                    int column = switch (ev.type()) {
                        case GOAL -> ours ? 0 : 1;
                        case YELLOW_CARD -> ours ? 2 : 5;
                        case BLUE_CARD -> ours ? 3 : 6;
                        case RED_CARD -> ours ? 4 : 7;
                    };
                    counts[bucket][column]++;
                    if (ev.type() == EventType.GOAL) {
                        goalsInSheet++;
                    }
                }
                if (goalsInSheet != m.homeGoals() + m.awayGoals()) {
                    mismatches++;
                }
            }
        }

        List<TimeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            int period = i / perPeriod + 1;
            int from = (i % perPeriod) * BUCKET_MINUTES;
            int to = Math.min(from + BUCKET_MINUTES, periodMinutes);
            buckets.add(bucket(period + "T " + from + "-" + to + "'", period, from, to, counts[i]));
        }
        List<TimeBucket> byPeriod = new ArrayList<>();
        for (int period = 1; period <= 2; period++) {
            int[] sum = new int[8];
            for (int i = (period - 1) * perPeriod; i < period * perPeriod; i++) {
                for (int c = 0; c < 8; c++) {
                    sum[c] += counts[i][c];
                }
            }
            byPeriod.add(bucket(period == 1 ? "1er tiempo" : "2do tiempo", period, 0, periodMinutes, sum));
        }
        return new TimeProfile(periodMinutes, played, withSheet, mismatches, buckets, byPeriod);
    }

    private static TimeBucket bucket(String label, int period, int from, int to, int[] c) {
        return new TimeBucket(label, period, from, to, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
    }

    /**
     * ¿El evento es del equipo del informe? Primero por team_id del acta; si falta, por club (logo) y,
     * en un cruce A vs B del mismo club, por el código del equipo.
     */
    static boolean isOwn(MatchEvent ev, TeamRef own, int rivalTeamId, int rivalClubId) {
        if (ev.teamId() == own.teamId()) {
            return true;
        }
        if (ev.teamId() == rivalTeamId) {
            return false;
        }
        if (own.clubId() != rivalClubId && ev.clubId() > 0) {
            return ev.clubId() == own.clubId();
        }
        return ev.teamCode().equalsIgnoreCase(own.code());
    }

    /** La fila publicada por la FPP; se marca desactualizada si tiene menos PJ que el calendario. */
    private static java.util.Optional<StandingSnapshot> snapshot(CompetitionData cd, TeamRef team, int playedInCalendar) {
        List<StandingRow> rows = cd.standings();
        return rows.stream()
                .filter(r -> r.code().equalsIgnoreCase(team.code()) || r.name().equalsIgnoreCase(team.name()))
                .findFirst()
                .map(r -> new StandingSnapshot(cd.competition().torneoLabel(), r.group(), r.position(),
                        (int) rows.stream().filter(o -> o.group().equals(r.group())).count(),
                        r.points(), r.played(), r.goalsFor(), r.goalsAgainst(), r.goalDiff(),
                        cd.standingsDate(), r.played() < playedInCalendar));
    }

    private static MatchLine line(Competition comp, Match m, int teamId) {
        boolean home = m.homeTeamId() == teamId;
        Integer gf = home ? m.homeGoals() : m.awayGoals();
        Integer ga = home ? m.awayGoals() : m.homeGoals();
        String outcome = gf == null ? null : gf > ga ? "G" : gf.equals(ga) ? "E" : "P";
        return new MatchLine(comp.torneoLabel(), m.round(), m.date(), m.time() == null ? "" : m.time().toString(),
                home, home ? m.awayName() : m.homeName(), gf, ga, outcome, m.venue());
    }

    private static List<PlayerLine> players(Map<Integer, List<PlayerStats>> byId, int teamGoals) {
        List<PlayerLine> lines = new ArrayList<>();
        for (List<PlayerStats> ps : byId.values()) {
            PlayerStats first = ps.getFirst();
            int games = ps.stream().mapToInt(PlayerStats::games).sum();
            int goals = ps.stream().mapToInt(PlayerStats::goals).sum();
            lines.add(new PlayerLine(first.playerId(), first.name(), games, goals,
                    ps.stream().mapToInt(PlayerStats::assists).sum(),
                    games == 0 ? 0 : (double) goals / games,
                    teamGoals == 0 ? 0 : Math.min(100.0, 100.0 * goals / teamGoals),
                    ps.stream().mapToInt(PlayerStats::penaltiesScored).sum(),
                    ps.stream().mapToInt(PlayerStats::penaltiesTaken).sum(),
                    ps.stream().mapToInt(PlayerStats::directFreeScored).sum(),
                    ps.stream().mapToInt(PlayerStats::directFreeTaken).sum(),
                    ps.stream().mapToInt(PlayerStats::blueCards).sum(),
                    ps.stream().mapToInt(PlayerStats::redCards).sum()));
        }
        lines.sort(Comparator.comparingInt(PlayerLine::goals).reversed()
                .thenComparing(Comparator.comparingInt(PlayerLine::assists).reversed())
                .thenComparing(PlayerLine::name));
        return lines;
    }

    private static List<RivalLine> rivals(List<MatchLine> results) {
        return results.stream().collect(Collectors.groupingBy(MatchLine::rival, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> {
                    List<MatchLine> ms = e.getValue();
                    return new RivalLine(e.getKey(), ms.size(),
                            (int) ms.stream().filter(m -> "G".equals(m.outcome())).count(),
                            (int) ms.stream().filter(m -> "E".equals(m.outcome())).count(),
                            (int) ms.stream().filter(m -> "P".equals(m.outcome())).count(),
                            ms.stream().mapToInt(MatchLine::goalsFor).sum(),
                            ms.stream().mapToInt(MatchLine::goalsAgainst).sum());
                })
                .sorted(Comparator.comparing(RivalLine::rival))
                .toList();
    }

    private static LeagueContext league(List<CompetitionData> data) {
        int matches = 0;
        int goals = 0;
        int teams = 0;
        for (CompetitionData cd : data) {
            teams += cd.competition().teams().size();
            for (Match m : cd.matches()) {
                if (m.played()) {
                    matches++;
                    goals += m.homeGoals() + m.awayGoals();
                }
            }
        }
        List<StandingRow> top3 = data.stream().flatMap(cd -> cd.standings().stream())
                .filter(r -> r.position() <= 3 && r.played() > 0).toList();
        double top3Gf = top3.stream().mapToDouble(r -> (double) r.goalsFor() / r.played()).average().orElse(0);
        List<PlayerStats> all = data.stream().flatMap(cd -> cd.players().stream()).toList();
        int fdS = all.stream().mapToInt(PlayerStats::directFreeScored).sum();
        int fdT = all.stream().mapToInt(PlayerStats::directFreeTaken).sum();
        int penS = all.stream().mapToInt(PlayerStats::penaltiesScored).sum();
        int penT = all.stream().mapToInt(PlayerStats::penaltiesTaken).sum();
        int cards = all.stream().mapToInt(p -> p.blueCards() + p.redCards()).sum();
        int teamGames = matches * 2;
        return new LeagueContext(teams, matches,
                teamGames == 0 ? 0 : (double) goals / teamGames, top3Gf,
                ReportData.pct(fdS, fdT), ReportData.pct(penS, penT),
                teamGames == 0 ? 0 : (double) cards / teamGames);
    }
}
