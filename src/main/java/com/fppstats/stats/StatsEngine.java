package com.fppstats.stats;

import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Models.Match;
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
import com.fppstats.stats.ReportData.TorneoLine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Calcula las estadísticas predefinidas a partir de los datos extraídos. Sin I/O: fácil de testear. */
public final class StatsEngine {

    /**
     * Datos ya extraídos de una competición.
     *
     * @param standingsDate fecha de generación de la tabla en SIDGAD (puede quedar atrasada respecto del calendario)
     */
    public record CompetitionData(Competition competition, List<StandingRow> standings, LocalDate standingsDate,
                                  List<Match> matches, List<PlayerStats> players) {
    }

    private static final Comparator<Match> CHRONO = Comparator
            .comparing(Match::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Match::round);

    private StatsEngine() {
    }

    public static ReportData build(ClubView club, String categoryLabel, String torneoLabel,
                                   List<CompetitionData> data, LocalDate today) {
        // Un mismo equipo tiene distinto teamId en Apertura y Clausura: se agrupa por código.
        Map<String, List<Entry>> byTeam = new LinkedHashMap<>();
        for (CompetitionData cd : data) {
            List<TeamRef> own = cd.competition().teams().stream().filter(t -> t.clubId() == club.id()).toList();
            for (TeamRef t : own) {
                byTeam.computeIfAbsent(teamKey(t, own.size(), club.code()), k -> new ArrayList<>()).add(new Entry(cd, t));
            }
        }
        List<TeamReport> teams = byTeam.entrySet().stream()
                .map(e -> team(e.getKey(), e.getValue(), today)).toList();
        List<Source> sources = data.stream()
                .map(cd -> new Source(cd.competition().name(), cd.competition().url())).toList();
        return new ReportData(new ReportData.ClubInfo(club.id(), club.code(), club.name()),
                categoryLabel, torneoLabel, today, sources, teams, league(data));
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

    private static TeamReport team(String key, List<Entry> entries, LocalDate today) {
        String name = entries.getLast().team().name();
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
            for (Match m : e.data().matches().stream().filter(m -> m.involves(teamId)).sorted(CHRONO).toList()) {
                MatchLine line = line(comp, m, teamId);
                if (m.played()) {
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
            byTorneo.add(new TorneoLine(comp.torneoLabel(), compRecord));
            snapshot(e.data(), e.team(), compRecord.played()).ifPresent(standings::add);
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

        List<PlayerLine> players = players(playersById, total.goalsFor());
        SetPieces setPieces = new SetPieces(
                players.stream().mapToInt(PlayerLine::directFreeScored).sum(),
                players.stream().mapToInt(PlayerLine::directFreeTaken).sum(),
                players.stream().mapToInt(PlayerLine::penaltiesScored).sum(),
                players.stream().mapToInt(PlayerLine::penaltiesTaken).sum());
        int blue = players.stream().mapToInt(PlayerLine::blueCards).sum();
        int red = players.stream().mapToInt(PlayerLine::redCards).sum();
        Discipline discipline = new Discipline(blue, red, total.played() == 0 ? 0 : (double) (blue + red) / total.played());

        return new TeamReport(key, name, standings, total, home, away, form, results, upcoming,
                biggestWin, worstLoss, players, setPieces, discipline, rivals(results), byTorneo);
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
