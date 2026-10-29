package com.madsports.stats;

import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.StandingRow;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.stats.ReportData.Discipline;
import com.madsports.stats.ReportData.KeeperLine;
import com.madsports.stats.ReportData.Fouls;
import com.madsports.stats.ReportData.LeagueContext;
import com.madsports.stats.ReportData.MatchLine;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.ReportData.TeamRecord;
import com.madsports.stats.ReportData.RivalLine;
import com.madsports.stats.ReportData.SetPieces;
import com.madsports.stats.ReportData.Source;
import com.madsports.stats.ReportData.StandingSnapshot;
import com.madsports.stats.ReportData.TeamReport;
import com.madsports.stats.ReportData.TimeBucket;
import com.madsports.stats.ReportData.TimeProfile;
import com.madsports.stats.ReportData.TournamentLine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    /**
     * @param goalkeepers arqueros de los equipos del club (por id_player): no entran en los goleadores
     */
    /**
     * @param startingKeepers el arquero titular del equipo del club en cada partido con planilla (por idp)
     */
    public record CompetitionData(Competition competition, List<StandingRow> standings, LocalDate standingsDate,
                                  List<Match> matches, List<PlayerStats> players, Map<Integer, List<MatchEvent>> sheets,
                                  Set<Integer> goalkeepers, Map<Integer, Keeper> startingKeepers) {

        public CompetitionData(Competition competition, List<StandingRow> standings, LocalDate standingsDate,
                               List<Match> matches, List<PlayerStats> players, Map<Integer, List<MatchEvent>> sheets,
                               Set<Integer> goalkeepers) {
            this(competition, standings, standingsDate, matches, players, sheets, goalkeepers, Map.of());
        }

        public CompetitionData(Competition competition, List<StandingRow> standings, LocalDate standingsDate,
                               List<Match> matches, List<PlayerStats> players, Map<Integer, List<MatchEvent>> sheets) {
            this(competition, standings, standingsDate, matches, players, sheets, Set.of(), Map.of());
        }
    }

    /** Un arquero, como figura en la planilla. */
    public record Keeper(int playerId, String name) {
    }

    private static final Comparator<Match> CHRONO = Comparator
            .comparing(Match::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Match::round);

    private StatsEngine() {
    }

    public static ReportData build(ClubView club, String categoryLabel, String tournamentLabel,
                                   List<CompetitionData> data, LocalDate today) {
        return build(club, categoryLabel, tournamentLabel, data, today, 0);
    }

    /**
     * @param lastMatches si es mayor que 0, récord, resultados, rivales y perfil por minuto se calculan solo con
     *                    los últimos N partidos jugados de cada equipo (las estadísticas de jugadores siguen siendo
     *                    de los torneos completos: SIDGAD no las publica por partido)
     */
    public static ReportData build(ClubView club, String categoryLabel, String tournamentLabel,
                                   List<CompetitionData> data, LocalDate today, int lastMatches) {
        return build(club, categoryLabel, tournamentLabel, data, today, lastMatches, null);
    }

    /**
     * @param teamKey solo ese equipo del club (p. ej. "CAVS B"); null = todos. Los equipos A y B de un club son
     *                equipos distintos y cada uno tiene su informe.
     */
    public static ReportData build(ClubView club, String categoryLabel, String tournamentLabel,
                                   List<CompetitionData> data, LocalDate today, int lastMatches, String teamKey) {
        // Un mismo equipo tiene distinto teamId en Apertura y Clausura: se agrupa por su clave (código del club + letra).
        Map<String, List<TeamEntry>> byTeam = new LinkedHashMap<>();
        boolean severalTeams = data.stream().anyMatch(cd -> ownTeams(cd.competition(), club).size() > 1);
        for (CompetitionData cd : data) {
            List<TeamRef> own = ownTeams(cd.competition(), club);
            Map<Integer, String> keys = TeamKeys.keys(own, club.code(), severalTeams);
            for (TeamRef t : own) {
                String key = keys.get(t.teamId());
                if (teamKey == null || teamKey.equals(key)) {
                    byTeam.computeIfAbsent(key, k -> new ArrayList<>()).add(new TeamEntry(cd, t));
                }
            }
        }
        LeagueContext league = league(data);
        List<TeamReport> teams = byTeam.entrySet().stream()
                .map(e -> team(e.getKey(), TeamKeys.displayName(club.name(), club.code(), e.getKey()),
                        e.getValue(), today, lastMatches, league))
                .toList();
        List<Source> sources = data.stream()
                .map(cd -> new Source(cd.competition().name(), cd.competition().url())).toList();
        return new ReportData(new ReportData.ClubInfo(club.id(), club.code(), club.name()),
                categoryLabel, tournamentLabel, today, lastMatches, sources, teams, league, List.of());
    }

    private static List<TeamRef> ownTeams(Competition c, ClubView club) {
        return c.teams().stream()
                .filter(t -> t.clubId() == club.id() && TeamKeys.belongs(t, club.code(), club.name()))
                .toList();
    }

    /** Partidos jugados del equipo en todas sus competiciones, en orden cronológico; los últimos N si N > 0. */
    static Set<Match> includedMatches(List<Match> playedChronologically, int lastMatches) {
        int skip = lastMatches > 0 ? Math.max(0, playedChronologically.size() - lastMatches) : 0;
        return new HashSet<>(playedChronologically.subList(skip, playedChronologically.size()));
    }

    private static TeamReport team(String key, String name, List<TeamEntry> entries, LocalDate today, int lastMatches,
                                   LeagueContext league) {
        List<Match> allPlayed = entries.stream()
                .flatMap(e -> e.data().matches().stream().filter(m -> m.played() && m.involves(e.team().teamId())))
                .sorted(CHRONO).toList();
        Set<Match> included = includedMatches(allPlayed, lastMatches);
        List<StandingSnapshot> standings = new ArrayList<>();
        List<MatchLine> results = new ArrayList<>();
        List<MatchLine> upcoming = new ArrayList<>();
        List<TournamentLine> byTournament = new ArrayList<>();
        Map<Integer, List<PlayerStats>> playersById = new LinkedHashMap<>();
        TeamRecord total = TeamRecord.EMPTY;
        TeamRecord home = TeamRecord.EMPTY;
        TeamRecord away = TeamRecord.EMPTY;

        for (TeamEntry e : entries) {
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
                byTournament.add(new TournamentLine(comp.tournamentLabel(), compRecord));
            }
            StandingsCalc.snapshot(e.data(), e.team(), playedInCalendar).ifPresent(standings::add);
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
        // Amarillas por jugador: solo las actas las traen (la tabla de SIDGAD no).
        Map<Integer, Integer> yellows = new HashMap<>();
        for (TeamEntry e : entries) {
            for (Match m : e.data().matches()) {
                if (m.played() && m.involves(e.team().teamId())) {
                    e.data().sheets().getOrDefault(m.idp(), List.of()).stream()
                            .filter(ev -> ev.type() == EventType.YELLOW_CARD && ev.playerId() > 0)
                            .forEach(ev -> yellows.merge(ev.playerId(), 1, Integer::sum));
                }
            }
        }
        List<PlayerLine> players = players(playersById, seasonGoals, yellows);
        SetPieces setPieces = new SetPieces(
                players.stream().mapToInt(PlayerLine::directFreeScored).sum(),
                players.stream().mapToInt(PlayerLine::directFreeTaken).sum(),
                players.stream().mapToInt(PlayerLine::penaltiesScored).sum(),
                players.stream().mapToInt(PlayerLine::penaltiesTaken).sum());
        int blue = players.stream().mapToInt(PlayerLine::blueCards).sum();
        int red = players.stream().mapToInt(PlayerLine::redCards).sum();
        TimeProfile timeProfile = TimeProfileCalc.timeProfile(entries, included);
        Discipline discipline = lastMatches > 0
                ? disciplineFromSheets(timeProfile)
                : new Discipline(-1, blue, red, seasonPlayed == 0 ? 0 : (double) (blue + red) / seasonPlayed,
                        seasonPlayed, false);

        Fouls fouls = new Fouls(timeProfile.matchesWithSheet(),
                timeProfile.byPeriod().stream().mapToInt(TimeBucket::foulsOwn).sum() + timeProfile.unrecorded().foulsOwn(),
                timeProfile.byPeriod().stream().mapToInt(TimeBucket::foulsRival).sum() + timeProfile.unrecorded().foulsRival(),
                league.foulsPerTeamGame());
        // Los arqueros no entran en las métricas de goles (goleadores, % de los goles, análisis); sus tarjetas y su
        // pelota parada sí cuentan arriba, en las del equipo.
        Set<Integer> goalkeepers = entries.stream().flatMap(e -> e.data().goalkeepers().stream()).collect(Collectors.toSet());
        List<PlayerLine> scorers = players.stream().filter(p -> !goalkeepers.contains(p.playerId())).toList();
        List<KeeperLine> keepers = keepers(entries, included);
        TeamReport base = new TeamReport(key, name, standings, total, home, away, form, results, upcoming,
                biggestWin, worstLoss, scorers, setPieces, discipline, rivals(results), byTournament, timeProfile, List.of(),
                fouls, players, keepers);
        return new TeamReport(key, name, standings, total, home, away, form, results, upcoming,
                biggestWin, worstLoss, scorers, setPieces, discipline, base.rivals(), byTournament, timeProfile,
                StrengthsAnalysis.evaluate(base, league), fouls, players, keepers);
    }

    /**
     * Los arqueros titulares del equipo: partidos que empezaron en el arco, goles recibidos en esos partidos y vallas
     * invictas. Solo los partidos con planilla (sin ella no se sabe quién atajó). Primero el de más partidos.
     */
    private static List<KeeperLine> keepers(List<TeamEntry> entries, Set<Match> included) {
        Map<Integer, int[]> stats = new LinkedHashMap<>();   // arquero -> {partidos, goles recibidos, vallas invictas}
        Map<Integer, String> names = new HashMap<>();
        for (TeamEntry e : entries) {
            for (Match m : e.data().matches()) {
                Keeper k = e.data().startingKeepers().get(m.idp());
                if (k == null || !included.contains(m) || !m.involves(e.team().teamId())) {
                    continue;
                }
                int against = m.homeTeamId() == e.team().teamId() ? m.awayGoals() : m.homeGoals();
                int[] s = stats.computeIfAbsent(k.playerId(), id -> new int[3]);
                s[0]++;
                s[1] += against;
                s[2] += against == 0 ? 1 : 0;
                names.putIfAbsent(k.playerId(), k.name());
            }
        }
        return stats.entrySet().stream()
                .map(x -> new KeeperLine(x.getKey(), names.get(x.getKey()), x.getValue()[0], x.getValue()[1],
                        x.getValue()[2]))
                .sorted(Comparator.comparingInt(KeeperLine::matches).reversed().thenComparing(KeeperLine::name))
                .toList();
    }

    /**
     * Con "últimos N" las tarjetas se cuentan solo en esos partidos, desde sus fichas (las estadísticas por
     * jugador de SIDGAD son del torneo completo). Partidos sin ficha no aportan tarjetas: se promedia sobre
     * los que tienen ficha.
     */
    private static Discipline disciplineFromSheets(TimeProfile tp) {
        List<TimeBucket> all = new ArrayList<>(tp.byPeriod());
        all.add(tp.unrecorded());
        int yellow = all.stream().mapToInt(TimeBucket::yellowOwn).sum();
        int blue = all.stream().mapToInt(TimeBucket::blueOwn).sum();
        int red = all.stream().mapToInt(TimeBucket::redOwn).sum();
        int matches = tp.matchesWithSheet();
        return new Discipline(yellow, blue, red, matches == 0 ? 0 : (double) (blue + red) / matches, matches, true);
    }

    /** Goles del equipo del informe en un partido (el equipo es el de la competición a la que pertenece el partido). */
    private static int ownGoals(Match m, List<TeamEntry> entries) {
        for (TeamEntry e : entries) {
            if (m.homeTeamId() == e.team().teamId()) {
                return m.homeGoals();
            }
            if (m.awayTeamId() == e.team().teamId()) {
                return m.awayGoals();
            }
        }
        return 0;
    }


    private static MatchLine line(Competition comp, Match m, int teamId) {
        boolean home = m.homeTeamId() == teamId;
        Integer gf = home ? m.homeGoals() : m.awayGoals();
        Integer ga = home ? m.awayGoals() : m.homeGoals();
        String outcome = gf == null ? null : gf > ga ? "G" : gf.equals(ga) ? "E" : "P";
        return new MatchLine(comp.tournamentLabel(), m.round(), m.date(), m.time() == null ? "" : m.time().toString(),
                home, home ? m.awayName() : m.homeName(), gf, ga, outcome, m.venue());
    }

    private static List<PlayerLine> players(Map<Integer, List<PlayerStats>> byId, int teamGoals,
                                            Map<Integer, Integer> yellows) {
        List<PlayerLine> lines = new ArrayList<>();
        for (List<PlayerStats> ps : byId.values()) {
            PlayerStats first = ps.getFirst();
            int games = ps.stream().mapToInt(PlayerStats::games).sum();
            int goals = ps.stream().mapToInt(PlayerStats::goals).sum();
            lines.add(new PlayerLine(first.playerId(), first.name(), games, goals,
                    games == 0 ? 0 : (double) goals / games,
                    teamGoals == 0 ? 0 : Math.min(100.0, 100.0 * goals / teamGoals),
                    ps.stream().mapToInt(PlayerStats::penaltiesScored).sum(),
                    ps.stream().mapToInt(PlayerStats::penaltiesTaken).sum(),
                    ps.stream().mapToInt(PlayerStats::directFreeScored).sum(),
                    ps.stream().mapToInt(PlayerStats::directFreeTaken).sum(),
                    yellows.getOrDefault(first.playerId(), 0),
                    ps.stream().mapToInt(PlayerStats::blueCards).sum(),
                    ps.stream().mapToInt(PlayerStats::redCards).sum()));
        }
        lines.sort(Comparator.comparingInt(PlayerLine::goals).reversed()
                .thenComparing(Comparator.comparingDouble(PlayerLine::goalsPerGame).reversed())
                .thenComparing(PlayerLine::name));
        return lines;
    }

    private static List<RivalLine> rivals(List<MatchLine> results) {
        return results.stream().collect(Collectors.groupingBy(MatchLine::opponent, LinkedHashMap::new, Collectors.toList()))
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
                .sorted(Comparator.comparing(RivalLine::opponent))
                .toList();
    }

    private static LeagueContext league(List<CompetitionData> data) {
        int matches = 0;
        int goals = 0;
        int teams = 0;
        int points = 0;
        for (CompetitionData cd : data) {
            teams += cd.competition().teams().size();
            for (Match m : cd.matches()) {
                if (m.played()) {
                    matches++;
                    goals += m.homeGoals() + m.awayGoals();
                    points += m.homeGoals().equals(m.awayGoals()) ? 2 : 3;
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
        int sheets = 0;
        int fouls = 0;
        for (CompetitionData cd : data) {
            for (Match m : cd.matches()) {
                List<MatchEvent> events = cd.sheets().getOrDefault(m.idp(), List.of());
                if (m.played() && !events.isEmpty()) {
                    sheets++;
                    fouls += (int) events.stream().filter(ev -> ev.type() == EventType.FOUL).count();
                }
            }
        }
        return new LeagueContext(teams, matches,
                teamGames == 0 ? 0 : (double) goals / teamGames, top3Gf,
                ReportData.pct(fdS, fdT), ReportData.pct(penS, penT),
                teamGames == 0 ? 0 : (double) cards / teamGames,
                teamGames == 0 ? 0 : 100.0 * points / (teamGames * 3),
                sheets == 0 ? 0 : (double) fouls / (2 * sheets), sheets);
    }
}