package com.madsports.stats;

import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.StandingRow;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.stats.ReportData.RoundPosition;
import com.madsports.stats.ReportData.StandingSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** La posición del equipo en la tabla publicada y su puesto fecha a fecha, rearmado con el calendario. Sin I/O. */
final class StandingsCalc {

    private StandingsCalc() {
    }

    /** La fila publicada por la FPP; se marca desactualizada si tiene menos PJ que el calendario. */
    static Optional<StandingSnapshot> snapshot(StatsEngine.CompetitionData cd, String tournament, TeamRef team,
                                               int playedInCalendar) {
        List<StandingRow> rows = cd.standings();
        return rows.stream()
                .filter(r -> r.code().equalsIgnoreCase(team.code()) || r.name().equalsIgnoreCase(team.name()))
                .findFirst()
                .map(r -> new StandingSnapshot(tournament, r.group(), r.position(),
                        (int) rows.stream().filter(o -> o.group().equals(r.group())).count(),
                        r.points(), r.played(), r.goalsFor(), r.goalsAgainst(), r.goalDiff(),
                        cd.standingsDate(), r.played() < playedInCalendar, evolution(cd, team, r.group())));
    }

    /**
     * Su puesto después de cada fecha en que jugó: se rearma la tabla de su zona fecha a fecha con los resultados del
     * calendario (3 puntos el triunfo y 1 el empate; a igual puntaje, diferencia de gol y goles a favor). Los equipos de
     * la zona salen de la tabla publicada; si no se pueden cruzar con el calendario, cuentan todos.
     */
    static List<RoundPosition> evolution(StatsEngine.CompetitionData cd, TeamRef team, String group) {
        Set<String> zone = cd.standings().stream().filter(r -> r.group().equals(group))
                .map(r -> r.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        java.util.function.Predicate<Match> inZone = m -> zone.contains(m.homeName().toLowerCase(Locale.ROOT))
                && zone.contains(m.awayName().toLowerCase(Locale.ROOT));
        List<Match> all = cd.matches().stream().filter(inZone).toList();
        if (all.stream().noneMatch(m -> m.involves(team.teamId()))) {
            all = cd.matches();
        }
        Map<Integer, int[]> table = new HashMap<>();   // equipo -> {puntos, goles a favor, goles en contra}
        all.forEach(m -> {
            table.putIfAbsent(m.homeTeamId(), new int[3]);
            table.putIfAbsent(m.awayTeamId(), new int[3]);
        });
        List<RoundPosition> out = new ArrayList<>();
        Map<Integer, List<Match>> byRound = all.stream().filter(Match::played)
                .collect(Collectors.groupingBy(Match::round, java.util.TreeMap::new, Collectors.toList()));
        for (Map.Entry<Integer, List<Match>> round : byRound.entrySet()) {
            boolean played = false;
            for (Match m : round.getValue()) {
                add(table.get(m.homeTeamId()), m.homeGoals(), m.awayGoals());
                add(table.get(m.awayTeamId()), m.awayGoals(), m.homeGoals());
                played |= m.involves(team.teamId());
            }
            int[] own = table.get(team.teamId());
            if (played && own != null) {
                Comparator<int[]> order = Comparator.<int[]>comparingInt(t -> t[0])
                        .thenComparingInt(t -> t[1] - t[2]).thenComparingInt(t -> t[1]);
                int position = 1 + (int) table.values().stream().filter(t -> order.compare(t, own) > 0).count();
                out.add(new RoundPosition(round.getKey(), position));
            }
        }
        return out;
    }

    private static void add(int[] row, int goalsFor, int goalsAgainst) {
        row[0] += goalsFor > goalsAgainst ? 3 : goalsFor == goalsAgainst ? 1 : 0;
        row[1] += goalsFor;
        row[2] += goalsAgainst;
    }
}
