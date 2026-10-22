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
import com.madsports.stats.ReportData.FoulSituation;
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
    public record CompetitionData(Competition competition, List<StandingRow> standings, LocalDate standingsDate,
                                  List<Match> matches, List<PlayerStats> players, Map<Integer, List<MatchEvent>> sheets,
                                  Set<Integer> goalkeepers) {

        public CompetitionData(Competition competition, List<StandingRow> standings, LocalDate standingsDate,
                               List<Match> matches, List<PlayerStats> players, Map<Integer, List<MatchEvent>> sheets) {
            this(competition, standings, standingsDate, matches, players, sheets, Set.of());
        }
    }

    static final int BUCKET_MINUTES = 5;

    /** Tiempo que el equipo juega con uno menos por cada sanción (reglamento de hockey sobre patines). */
    static final int BLUE_CARD_SECONDS = 2 * 60;
    static final int RED_CARD_SECONDS = 4 * 60;

    /** Período que SIDGAD usa para el descanso (config_params: all_periods:1,10,2). */
    static final int HALFTIME_PERIOD = 10;

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
        Map<String, List<Entry>> byTeam = new LinkedHashMap<>();
        boolean severalTeams = data.stream().anyMatch(cd -> ownTeams(cd.competition(), club).size() > 1);
        for (CompetitionData cd : data) {
            List<TeamRef> own = ownTeams(cd.competition(), club);
            Map<Integer, String> keys = TeamKeys.keys(own, club.code(), severalTeams);
            for (TeamRef t : own) {
                String key = keys.get(t.teamId());
                if (teamKey == null || teamKey.equals(key)) {
                    byTeam.computeIfAbsent(key, k -> new ArrayList<>()).add(new Entry(cd, t));
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

    private record Entry(CompetitionData data, TeamRef team) {
    }

    private static TeamReport team(String key, String name, List<Entry> entries, LocalDate today, int lastMatches,
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
                byTournament.add(new TournamentLine(comp.tournamentLabel(), compRecord));
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
        // Amarillas por jugador: solo las actas las traen (la tabla de SIDGAD no).
        Map<Integer, Integer> yellows = new HashMap<>();
        for (Entry e : entries) {
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
        TimeProfile timeProfile = timeProfile(entries, included);
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
        TeamReport base = new TeamReport(key, name, standings, total, home, away, form, results, upcoming,
                biggestWin, worstLoss, scorers, setPieces, discipline, rivals(results), byTournament, timeProfile, List.of(),
                fouls, players);
        return new TeamReport(key, name, standings, total, home, away, form, results, upcoming,
                biggestWin, worstLoss, scorers, setPieces, discipline, base.rivals(), byTournament, timeProfile,
                StrengthsAnalysis.evaluate(base, league), fouls, players);
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
    /** Columnas por tramo: goles, tarjetas (amarilla, azul, roja) y faltas; propias y de los rivales. */
    private static final int COLS = 10;

    private static TimeProfile timeProfile(List<Entry> entries, Set<Match> included) {
        int periodMinutes = entries.stream().mapToInt(e -> e.data().competition().periodMinutes()).max()
                .orElse(Competition.DEFAULT_PERIOD_MINUTES);
        int perPeriod = (periodMinutes + BUCKET_MINUTES - 1) / BUCKET_MINUTES;
        int[][] counts = new int[2 * perPeriod][COLS];
        // Marcador al empezar cada tramo: suma de diferencias y partidos ganando / empatando / perdiendo.
        double[] diffSum = new double[2 * perPeriod];
        int[][] situation = new int[2 * perPeriod][3];
        int played = 0;
        int withSheet = 0;
        int mismatches = 0;
        int unrecordedFor = 0;
        int unrecordedAgainst = 0;
        int shortHanded = 0;
        int powerPlay = 0;
        int[] unrecordedCards = new int[COLS];
        // Faltas propias según el marcador en el minuto de la falta, y segundos en cada situación.
        int[] foulsBySituation = new int[3];
        int[] secondsBySituation = new int[3];
        ShortHandedCalc shortHandedGoals = new ShortHandedCalc();

        for (Entry e : entries) {
            TeamRef own = e.team();
            for (Match m : e.data().matches()) {
                if (!m.involves(own.teamId()) || !included.contains(m)) {
                    continue;
                }
                played++;
                boolean home = m.homeTeamId() == own.teamId();
                int resultFor = home ? m.homeGoals() : m.awayGoals();
                int resultAgainst = home ? m.awayGoals() : m.homeGoals();
                List<MatchEvent> events = e.data().sheets().getOrDefault(m.idp(), List.of());
                if (events.isEmpty()) {
                    // Sin ficha: se conoce el resultado pero no el minuto de los goles ni las tarjetas.
                    unrecordedFor += resultFor;
                    unrecordedAgainst += resultAgainst;
                    continue;
                }
                withSheet++;
                int rivalTeamId = home ? m.awayTeamId() : m.homeTeamId();
                int rivalClubId = home ? m.awayClubId() : m.homeClubId();
                int goalsInSheet = 0;
                int ownGoalsInSheet = 0;
                int rivalGoalsInSheet = 0;
                int matchPeriod = e.data().competition().periodMinutes();
                List<int[]> ownSanctions = new ArrayList<>();
                List<int[]> rivalSanctions = new ArrayList<>();
                List<Integer> ownGoalTimes = new ArrayList<>();
                List<Integer> rivalGoalTimes = new ArrayList<>();
                List<Integer> ownFoulTimes = new ArrayList<>();
                int ownBlues = 0;
                int ownReds = 0;
                for (MatchEvent ev : events) {
                    boolean ours = isOwn(ev, own, rivalTeamId, rivalClubId);
                    int column = switch (ev.type()) {
                        case GOAL -> ours ? 0 : 1;
                        case YELLOW_CARD -> ours ? 2 : 5;
                        case BLUE_CARD -> ours ? 3 : 6;
                        case RED_CARD -> ours ? 4 : 7;
                        case FOUL -> ours ? 8 : 9;
                    };
                    // SIDGAD: período 10 = descanso (la sanción se cumple desde el inicio del 2T); 11 y otros = fuera
                    // del juego (p. ej. después del partido): la tarjeta cuenta, pero sin minuto ni inferioridad.
                    int period = ev.period() == HALFTIME_PERIOD ? 2 : ev.period();
                    int minute = ev.period() == HALFTIME_PERIOD ? 0 : ev.minute();
                    int second = ev.period() == HALFTIME_PERIOD ? 0 : ev.second();
                    if (period < 1 || period > 2) {
                        if (ev.type() != EventType.GOAL) {
                            unrecordedCards[column]++;
                        }
                        continue;
                    }
                    int bucket = (period - 1) * perPeriod + Math.min(minute / BUCKET_MINUTES, perPeriod - 1);
                    counts[bucket][column]++;
                    int start = (period - 1) * matchPeriod * 60 + minute * 60 + second;
                    int seconds = ev.type() == EventType.BLUE_CARD ? BLUE_CARD_SECONDS
                            : ev.type() == EventType.RED_CARD ? RED_CARD_SECONDS : 0;
                    if (seconds > 0) {
                        (ours ? ownSanctions : rivalSanctions).add(new int[]{start, start + seconds});
                        if (ours) {
                            ownBlues += ev.type() == EventType.BLUE_CARD ? 1 : 0;
                            ownReds += ev.type() == EventType.RED_CARD ? 1 : 0;
                        }
                    }
                    if (ev.type() == EventType.FOUL && ours) {
                        ownFoulTimes.add(start);
                    }
                    if (ev.type() == EventType.GOAL) {
                        goalsInSheet++;
                        if (ours) {
                            ownGoalsInSheet++;
                            ownGoalTimes.add(start);
                        } else {
                            rivalGoalsInSheet++;
                            rivalGoalTimes.add(start);
                        }
                    }
                }
                if (goalsInSheet != m.homeGoals() + m.awayGoals()) {
                    mismatches++;
                }
                // Cómo iba el partido al empezar cada tramo, según el minuto de los goles de la ficha.
                for (int i = 0; i < 2 * perPeriod; i++) {
                    int startOfBucket = (i / perPeriod) * matchPeriod * 60 + (i % perPeriod) * BUCKET_MINUTES * 60;
                    int diff = (int) ownGoalTimes.stream().filter(t -> t < startOfBucket).count()
                            - (int) rivalGoalTimes.stream().filter(t -> t < startOfBucket).count();
                    diffSum[i] += diff;
                    situation[i][diff > 0 ? 0 : diff == 0 ? 1 : 2]++;
                }
                // Faltas según el marcador y tiempo ganando / empatando / perdiendo.
                for (int t : ownFoulTimes) {
                    int diff = (int) ownGoalTimes.stream().filter(g -> g < t).count()
                            - (int) rivalGoalTimes.stream().filter(g -> g < t).count();
                    foulsBySituation[situationIndex(diff)]++;
                }
                accumulateSituationSeconds(ownGoalTimes, rivalGoalTimes, 2 * matchPeriod * 60, secondsBySituation);
                // Ficha incompleta: los goles del resultado que no figuran en ella quedan sin minuto.
                unrecordedFor += Math.max(0, resultFor - ownGoalsInSheet);
                unrecordedAgainst += Math.max(0, resultAgainst - rivalGoalsInSheet);
                shortHanded += sanctionSeconds(ownSanctions, 2 * matchPeriod * 60);
                shortHandedGoals.add(m.date(), home ? m.awayName() : m.homeName(), home, ownSanctions, ownBlues, ownReds,
                        ownGoalTimes, rivalGoalTimes, 2 * matchPeriod * 60, resultFor, resultAgainst);
                powerPlay += sanctionSeconds(rivalSanctions, 2 * matchPeriod * 60);
            }
        }

        double sheets = Math.max(1, withSheet);
        List<TimeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            int period = i / perPeriod + 1;
            int from = (i % perPeriod) * BUCKET_MINUTES;
            int to = Math.min(from + BUCKET_MINUTES, periodMinutes);
            buckets.add(bucket(period + "T " + from + "-" + to + "'", period, from, to, counts[i],
                    diffSum[i] / sheets, situation[i]));
        }
        List<TimeBucket> byPeriod = new ArrayList<>();
        for (int period = 1; period <= 2; period++) {
            int first = (period - 1) * perPeriod;
            int[] sum = new int[COLS];
            for (int i = first; i < period * perPeriod; i++) {
                for (int c = 0; c < COLS; c++) {
                    sum[c] += counts[i][c];
                }
            }
            byPeriod.add(bucket(period == 1 ? "1er tiempo" : "2do tiempo", period, 0, periodMinutes, sum,
                    diffSum[first] / sheets, situation[first]));
        }
        unrecordedCards[0] = unrecordedFor;
        unrecordedCards[1] = unrecordedAgainst;
        TimeBucket unrecorded = bucket("Sin minuto registrado", 0, 0, 0, unrecordedCards, 0, new int[3]);
        return new TimeProfile(periodMinutes, played, withSheet, mismatches, buckets, byPeriod, unrecorded,
                shortHanded, powerPlay, new FoulSituation(foulsBySituation[0], foulsBySituation[1], foulsBySituation[2],
                        secondsBySituation[0], secondsBySituation[1], secondsBySituation[2]), shortHandedGoals.result());
    }

    /** 0 = ganando, 1 = empatando, 2 = perdiendo. */
    private static int situationIndex(int goalDiff) {
        return goalDiff > 0 ? 0 : goalDiff == 0 ? 1 : 2;
    }

    /** Suma los segundos que el equipo estuvo ganando, empatando y perdiendo, según el minuto de los goles. */
    static void accumulateSituationSeconds(List<Integer> ownGoals, List<Integer> rivalGoals, int matchSeconds,
                                           int[] seconds) {
        List<int[]> goals = new ArrayList<>();
        ownGoals.forEach(t -> goals.add(new int[]{Math.min(t, matchSeconds), 1}));
        rivalGoals.forEach(t -> goals.add(new int[]{Math.min(t, matchSeconds), -1}));
        goals.sort(Comparator.comparingInt(g -> g[0]));
        int diff = 0;
        int prev = 0;
        for (int[] g : goals) {
            seconds[situationIndex(diff)] += g[0] - prev;
            diff += g[1];
            prev = g[0];
        }
        seconds[situationIndex(diff)] += matchSeconds - prev;
    }

    /**
     * Segundos de sanción: se suma cada sanción, aunque se superponga con otra (dos azules a la vez = 4').
     * La sanción se cumple completa aunque el rival haga un gol. Una sanción del final del 1T sigue en el 2T;
     * al final del partido se corta.
     */
    static int sanctionSeconds(List<int[]> intervals, int matchSeconds) {
        int total = 0;
        for (int[] i : intervals) {
            total += Math.max(0, Math.min(i[1], matchSeconds) - Math.max(i[0], 0));
        }
        return total;
    }

    private static TimeBucket bucket(String label, int period, int from, int to, int[] c,
                                     double avgGoalDiff, int[] situation) {
        return new TimeBucket(label, period, from, to, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9],
                avgGoalDiff, situation[0], situation[1], situation[2]);
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
                .map(r -> new StandingSnapshot(cd.competition().tournamentLabel(), r.group(), r.position(),
                        (int) rows.stream().filter(o -> o.group().equals(r.group())).count(),
                        r.points(), r.played(), r.goalsFor(), r.goalsAgainst(), r.goalDiff(),
                        cd.standingsDate(), r.played() < playedInCalendar));
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
