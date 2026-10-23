package com.madsports.report;

import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Competition.Tournament;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.StandingRow;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.stats.ReportData;
import com.madsports.stats.StatsEngine;
import com.madsports.stats.StatsEngine.CompetitionData;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Datos ficticios para el informe modelo. Clubes y jugadores inventados ("Club Ejemplo", "Rival Norte"...),
 * para no atribuir resultados falsos a clubes o personas reales. Determinístico: misma semilla, mismo informe.
 * Pasa por el mismo {@link StatsEngine} que los informes reales, así el modelo muestra todas las secciones.
 */
final class SampleData {

    static final ClubView CLUB = new ClubView(1, "CEJ", "CLUB EJEMPLO", List.of());

    private static final String[][] TEAMS = {
            {"CEJ", "CLUB EJEMPLO"}, {"RNO", "RIVAL NORTE"}, {"RSU", "RIVAL SUR"}, {"RES", "RIVAL ESTE"},
            {"ROE", "RIVAL OESTE"}, {"RCE", "RIVAL CENTRO"}, {"RPU", "RIVAL PUERTO"}, {"RPA", "RIVAL PARQUE"}};

    private static final String[] PLAYERS = {
            "Tomás Ejemplo", "Lucas Modelo", "Mateo Muestra", "Bruno Prueba", "Santiago Ficticio",
            "Franco Demo", "Julián Ensayo", "Nicolás Borrador", "Ignacio Boceto"};

    /** Peso de cada jugador del club al repartir los goles (los primeros son los goleadores). */
    private static final int[] SCORER_WEIGHT = {9, 6, 5, 3, 2, 2, 1, 1, 0};

    private static final int PERIOD_MINUTES = 25;
    private static final int OPENING_ROUNDS_PLAYED = 7;
    private static final int CLOSING_ROUNDS_PLAYED = 4;

    private SampleData() {
    }

    static ReportData build(LocalDate today) {
        return build(today, new Category("PRIMERA", "MASCULINO"), 2026, 9001);
    }

    /**
     * Una categoría del club del ejemplo, con sus partidos de Apertura y Clausura generados con la semilla dada.
     *
     * @param compId id de la competición del Apertura (el Clausura es el siguiente)
     */
    static ReportData build(LocalDate today, Category category, long seed, int compId) {
        Random rnd = new Random(seed);
        List<CompetitionData> data = List.of(
                competition(compId, Tournament.OPENING, category, today.minusWeeks(30), OPENING_ROUNDS_PLAYED, today, rnd),
                competition(compId + 1, Tournament.CLOSING, category,
                        today.minusWeeks(CLOSING_ROUNDS_PLAYED).plusDays(1), CLOSING_ROUNDS_PLAYED, today, rnd));
        ReportData report = StatsEngine.build(CLUB, category.label(), "Temporada " + today.getYear() + " · Apertura + Clausura",
                data, today);
        long total = data.stream().mapToLong(cd -> cd.sheets().size()).sum();
        long missing = data.stream().flatMap(cd -> cd.sheets().values().stream()).filter(List::isEmpty).count();
        return report.withNotPublished(List.of("Actas de partido: " + missing + " de " + total));
    }

    private static CompetitionData competition(int id, Tournament tournament, Category category, LocalDate firstRound,
                                               int roundsPlayed, LocalDate today, Random rnd) {
        int base = id * 100;
        List<TeamRef> teams = new ArrayList<>();
        for (int i = 0; i < TEAMS.length; i++) {
            teams.add(new TeamRef(base + i, i + 1, TEAMS[i][0], TEAMS[i][1]));
        }
        String name = "TORNEO " + tournament.sidgadName() + " " + category.label().toUpperCase() + " (EJEMPLO)";
        Competition comp = new Competition(id, name, 3, 40, tournament, category, 1, teams, PERIOD_MINUTES);

        List<Match> matches = new ArrayList<>();
        Map<Integer, List<MatchEvent>> sheets = new HashMap<>();
        Map<Integer, int[]> playerTotals = new LinkedHashMap<>();
        Map<Integer, StatsEngine.Keeper> keepers = new HashMap<>();
        int rounds = TEAMS.length - 1;
        for (int round = 1; round <= rounds; round++) {
            LocalDate date = firstRound.plusWeeks(round - 1);
            boolean played = round <= roundsPlayed && date.isBefore(today);
            for (int[] pair : roundRobin(round, TEAMS.length)) {
                TeamRef home = teams.get(pair[0]);
                TeamRef away = teams.get(pair[1]);
                int idp = base * 10 + round * 10 + pair[0];
                Integer hg = played ? goals(rnd, home) : null;
                Integer ag = played ? goals(rnd, away) : null;
                matches.add(new Match(idp, round, played ? date : null, LocalTime.of(18 + (pair[0] % 3), 0),
                        "CANCHA " + home.name(), home.teamId(), home.clubId(), home.name(),
                        away.teamId(), away.clubId(), away.name(), hg, ag));
                boolean clubMatch = home.clubId() == CLUB.id() || away.clubId() == CLUB.id();
                // Una ficha "no publicada" en el Apertura, para mostrar la fila "Sin minuto registrado".
                boolean sheetMissing = tournament == Tournament.OPENING && round == 3;
                if (played && clubMatch && !sheetMissing) {
                    sheets.put(idp, events(rnd, home, hg, away, ag, playerTotals));
                    // El arquero titular del club: casi siempre el primero, a veces el suplente.
                    keepers.put(idp, rnd.nextDouble() < 0.75 ? KEEPERS[0] : KEEPERS[1]);
                } else if (played && clubMatch) {
                    sheets.put(idp, List.of());
                    assignGoals(rnd, home.clubId() == CLUB.id() ? hg : ag, playerTotals);
                }
            }
        }
        return new CompetitionData(comp, standings(teams, matches), today, matches,
                players(teams, playerTotals, base, rnd), sheets, Set.of(), keepers);
    }

    /** Arqueros ficticios del club (para la sección de arqueros del ejemplo). */
    private static final StatsEngine.Keeper[] KEEPERS = {new StatsEngine.Keeper(990001, "Pablo Arquetipo"),
            new StatsEngine.Keeper(990002, "Diego Suplente")};

    /** Fixture de todos contra todos (método del círculo). */
    private static List<int[]> roundRobin(int round, int n) {
        List<int[]> pairs = new ArrayList<>();
        List<Integer> rot = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            rot.add(i);
        }
        java.util.Collections.rotate(rot, round - 1);
        List<Integer> order = new ArrayList<>();
        order.add(0);
        order.addAll(rot);
        for (int i = 0; i < n / 2; i++) {
            int a = order.get(i);
            int b = order.get(n - 1 - i);
            pairs.add(round % 2 == 0 ? new int[]{a, b} : new int[]{b, a});
        }
        return pairs;
    }

    /** El club del ejemplo convierte un poco más que el promedio, para que el análisis tenga contrastes. */
    private static int goals(Random rnd, TeamRef team) {
        int g = rnd.nextInt(6);
        return team.clubId() == CLUB.id() ? g + 1 + rnd.nextInt(2) : g;
    }

    private static List<MatchEvent> events(Random rnd, TeamRef home, int hg, TeamRef away, int ag,
                                           Map<Integer, int[]> playerTotals) {
        List<MatchEvent> ev = new ArrayList<>();
        for (TeamRef team : List.of(home, away)) {
            boolean ours = team.clubId() == CLUB.id();
            int goals = team == home ? hg : ag;
            List<Integer> scorers = ours ? assignGoals(rnd, goals, playerTotals) : null;
            for (int i = 0; i < goals; i++) {
                // Más goles en el 2do tiempo y sobre el final, como en un partido real.
                int abs = Math.min(2 * PERIOD_MINUTES - 1, (int) (Math.sqrt(rnd.nextDouble()) * 2 * PERIOD_MINUTES));
                String player = ours ? PLAYERS[scorers.get(i)] : "";
                ev.add(event(abs, rnd, EventType.GOAL, team, player));
            }
            int cardsScale = ours ? 1 : 2;
            for (int i = 0; i < rnd.nextInt(2 + cardsScale); i++) {
                ev.add(event(rnd.nextInt(2 * PERIOD_MINUTES), rnd, EventType.BLUE_CARD, team, ""));
            }
            if (rnd.nextInt(3) == 0) {
                // La amarilla del club, a un jugador según el minuto (sin sacar más números al azar: el resto del
                // ejemplo queda igual).
                int abs = rnd.nextInt(2 * PERIOD_MINUTES);
                MatchEvent e = event(abs, rnd, EventType.YELLOW_CARD, team, "");
                int p = abs % PLAYERS.length;
                ev.add(ours ? new MatchEvent(e.period(), e.minute(), e.second(), e.type(), e.teamId(), e.clubId(),
                        e.teamCode(), PLAYERS[p], 1000 + p) : e);
            }
            for (int i = 0; i < 4 + rnd.nextInt(7); i++) {
                ev.add(event(rnd.nextInt(2 * PERIOD_MINUTES), rnd, EventType.FOUL, team, ""));
            }
            if (!ours && rnd.nextInt(8) == 0) {
                ev.add(event(PERIOD_MINUTES + rnd.nextInt(PERIOD_MINUTES), rnd, EventType.RED_CARD, team, ""));
            }
        }
        return ev;
    }

    private static MatchEvent event(int absMinute, Random rnd, EventType type, TeamRef team, String player) {
        return new MatchEvent(absMinute / PERIOD_MINUTES + 1, absMinute % PERIOD_MINUTES, rnd.nextInt(60), type,
                team.teamId(), team.clubId(), team.code(), player);
    }

    /** Reparte goles entre los jugadores del club y acumula goles/asistencias por jugador. */
    private static List<Integer> assignGoals(Random rnd, int goals, Map<Integer, int[]> totals) {
        int weightSum = 0;
        for (int w : SCORER_WEIGHT) {
            weightSum += w;
        }
        List<Integer> scorers = new ArrayList<>();
        for (int g = 0; g < goals; g++) {
            int r = rnd.nextInt(weightSum);
            int p = 0;
            while (r >= SCORER_WEIGHT[p]) {
                r -= SCORER_WEIGHT[p++];
            }
            scorers.add(p);
            totals.computeIfAbsent(p, k -> new int[2])[0]++;
            if (rnd.nextInt(3) == 0) {
                totals.computeIfAbsent((p + 1 + rnd.nextInt(3)) % PLAYERS.length, k -> new int[2])[1]++;
            }
        }
        return scorers;
    }

    private static List<PlayerStats> players(List<TeamRef> teams, Map<Integer, int[]> totals, int base, Random rnd) {
        TeamRef club = teams.getFirst();
        List<PlayerStats> list = new ArrayList<>();
        for (int p = 0; p < PLAYERS.length; p++) {
            int[] t = totals.getOrDefault(p, new int[2]);
            int fdTaken = p < 3 ? 1 + rnd.nextInt(4) : 0;
            int penTaken = p < 2 ? rnd.nextInt(3) : 0;
            list.add(new PlayerStats(1000 + p, club.teamId(), club.code(), club.clubId(), PLAYERS[p],
                    t[0], 7 - (p > 6 ? 2 : 0), t[1],
                    rnd.nextInt(penTaken + 1), penTaken, rnd.nextInt(fdTaken + 1), fdTaken,
                    p % 3 == 0 ? 1 + rnd.nextInt(2) : rnd.nextInt(2), 0));
        }
        // El id de jugador es el mismo en todas las competiciones (como en SIDGAD): así se suman Apertura y Clausura.
        // Rivales: solo hacen falta sus totales para los promedios de liga (pelota parada y tarjetas).
        for (TeamRef t : teams.subList(1, teams.size())) {
            int fdTaken = 2 + rnd.nextInt(5);
            int penTaken = 1 + rnd.nextInt(3);
            list.add(new PlayerStats(2000 + t.clubId(), t.teamId(), t.code(), t.clubId(), "Jugador " + t.code(),
                    0, 7, 0, rnd.nextInt(penTaken + 1), penTaken, rnd.nextInt(fdTaken + 1), fdTaken,
                    2 + rnd.nextInt(5), rnd.nextInt(2)));
        }
        return list;
    }

    private static List<StandingRow> standings(List<TeamRef> teams, List<Match> matches) {
        Map<Integer, int[]> s = new LinkedHashMap<>(); // pts, pj, pg, pe, pp, gf, gc
        teams.forEach(t -> s.put(t.teamId(), new int[7]));
        for (Match m : matches) {
            if (!m.played()) {
                continue;
            }
            add(s.get(m.homeTeamId()), m.homeGoals(), m.awayGoals());
            add(s.get(m.awayTeamId()), m.awayGoals(), m.homeGoals());
        }
        List<TeamRef> sorted = teams.stream().sorted(Comparator
                .comparingInt((TeamRef t) -> -s.get(t.teamId())[0])
                .thenComparingInt(t -> -(s.get(t.teamId())[5] - s.get(t.teamId())[6]))
                .thenComparingInt(t -> -s.get(t.teamId())[5])).toList();
        List<StandingRow> rows = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            TeamRef t = sorted.get(i);
            int[] r = s.get(t.teamId());
            rows.add(new StandingRow("", i + 1, t.clubId(), t.name(), t.code(), r[0], r[1], r[2], r[3], r[4],
                    r[5], r[6], r[5] - r[6]));
        }
        return rows;
    }

    private static void add(int[] r, int gf, int ga) {
        r[1]++;
        r[5] += gf;
        r[6] += ga;
        if (gf > ga) {
            r[0] += 3;
            r[2]++;
        } else if (gf == ga) {
            r[0] += 1;
            r[3]++;
        } else {
            r[4]++;
        }
    }
}
