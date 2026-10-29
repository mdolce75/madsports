package com.madsports.player;

import com.madsports.report.ReportService.Scope;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Competition.Tournament;
import com.madsports.sidgad.model.Models.EventType;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.stats.ReportData;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Datos ficticios para el informe modelo de jugador: "Tomás Ejemplo", de Juniors, que también juega en Primera del
 * "Club Ejemplo". Se arman partidos, planillas y eventos inventados y se pasan por el mismo
 * {@link PlayerReportService#assemble} que los informes reales, así el modelo muestra todas las secciones con cifras
 * coherentes. Determinístico: misma semilla, mismo informe.
 */
public final class SamplePlayerData {

    static final int PLAYER_ID = 1;
    static final int CLUB_ID = 1;
    private static final String[][] TEAMS = {
            {"CEJ", "CLUB EJEMPLO"}, {"RNO", "RIVAL NORTE"}, {"RSU", "RIVAL SUR"}, {"RES", "RIVAL ESTE"},
            {"ROE", "RIVAL OESTE"}, {"RCE", "RIVAL CENTRO"}, {"RPU", "RIVAL PUERTO"}, {"RPA", "RIVAL PARQUE"}};
    private static final List<String> LEVELS = List.of("INFANTIL", "PREJUVENIL", "JUVENIL", "JUNIORS", "RESERVA", "PRIMERA");
    private static final int PERIOD_MINUTES = 25;
    /** Compañeros ficticios de campo. */
    private static final String[] MATES = {"Lucas Modelo", "Mateo Muestra", "Bruno Prueba", "Franco Demo"};

    private SamplePlayerData() {
    }

    /** @param logo escudo del club ficticio (data URI) */
    public static PlayerReport build(LocalDate today, String logo) {
        Random rnd = new Random(2026);
        Category juniors = new Category("JUNIORS", "MASCULINO");
        Category primera = new Category("PRIMERA", "MASCULINO");
        List<TeamInput> inputs = List.of(
                team(9101, Tournament.OPENING, juniors, today.minusWeeks(30), 9, 1.0, false, today, rnd),
                team(9102, Tournament.CLOSING, juniors, today.minusWeeks(5), 5, 1.0, false, today, rnd),
                team(9201, Tournament.OPENING, primera, today.minusWeeks(30).plusDays(1), 9, 0.55, true, today, rnd),
                team(9202, Tournament.CLOSING, primera, today.minusWeeks(5).plusDays(1), 5, 0.6, false, today, rnd));
        PlayerReportService.Query q = new PlayerReportService.Query(PLAYER_ID, "Tomás Ejemplo", "Tomás Ejemplo",
                CLUB_ID, today.getYear(), Scope.SEASON, Eligibility.levels("JUNIORS"), "MASCULINO");
        return PlayerReportAssembler.assemble(q, new ReportData.ClubInfo(CLUB_ID, "CEJ", "CLUB EJEMPLO", logo), today,
                        LEVELS, inputs)
                .withLeagueRanks(java.util.Map.of(
                        juniors.levelLabel() + " " + juniors.branchLabel(), new PlayerReport.LeagueRank(4, 96, 0, 21),
                        primera.levelLabel() + " " + primera.branchLabel(), new PlayerReport.LeagueRank(18, 88, 0, 25)));
    }

    /**
     * Un equipo del club en un torneo: {@code rounds} fechas jugadas, el jugador citado con probabilidad
     * {@code presence}; con {@code missingSheet}, a la última fecha le falta el acta (como pasa en SIDGAD).
     */
    private static TeamInput team(int id, Tournament tournament, Category category, LocalDate first,
                                                      int rounds, double presence, boolean missingSheet,
                                                      LocalDate today, Random rnd) {
        int base = id * 100;
        List<TeamRef> teams = new ArrayList<>();
        for (int i = 0; i < TEAMS.length; i++) {
            teams.add(new TeamRef(base + i, i + 1, TEAMS[i][0], TEAMS[i][1]));
        }
        Competition comp = new Competition(id, "TORNEO " + tournament.sidgadName() + " " + category.level() + " MASCULINO (EJEMPLO)",
                3, 40, tournament, category, 1, teams, PERIOD_MINUTES);
        String label = category.levelLabel() + " " + category.branchLabel();

        List<Match> matches = new ArrayList<>();
        Map<Integer, List<LineupPlayer>> sheets = new LinkedHashMap<>();
        Map<Integer, List<MatchEvent>> events = new LinkedHashMap<>();
        for (int r = 0; r < rounds; r++) {
            LocalDate date = first.plusWeeks(r);
            if (!date.isBefore(today)) {
                break;
            }
            int opponent = 1 + (r % (TEAMS.length - 1));
            boolean home = r % 2 == 0;
            int own = rnd.nextInt(6);
            int against = rnd.nextInt(6);
            int idp = base + 50 + r;
            matches.add(new Match(idp, r + 1, date, LocalTime.of(18, 0), "", home ? base : base + opponent,
                    home ? CLUB_ID : opponent + 1, home ? TEAMS[0][1] : TEAMS[opponent][1], home ? base + opponent : base,
                    home ? opponent + 1 : CLUB_ID, home ? TEAMS[opponent][1] : TEAMS[0][1],
                    home ? own : against, home ? against : own));
            boolean lastWithoutSheet = missingSheet && r == rounds - 1;
            if (lastWithoutSheet) {
                sheets.put(idp, List.of());
                events.put(idp, List.of());
                continue;
            }
            List<LineupPlayer> lineup = new ArrayList<>();
            List<MatchEvent> ev = new ArrayList<>();
            // Compañeros de equipo (para que la planilla no tenga un solo jugador).
            lineup.add(new LineupPlayer(2, TEAMS[0][1], home, "1", "Arquero Muestra", true, 0, 0, 0, 0));
            if (rnd.nextDouble() < presence) {
                int goals = Math.min(own, rnd.nextInt(4));
                int yellow = rnd.nextDouble() < 0.18 ? 1 : 0;
                int blue = rnd.nextDouble() < 0.22 ? 1 : 0;
                int red = rnd.nextDouble() < 0.04 ? 1 : 0;
                int penTaken = rnd.nextDouble() < 0.25 ? 1 : 0;
                int penScored = penTaken == 1 && rnd.nextDouble() < 0.6 ? 1 : 0;
                int fdTaken = rnd.nextDouble() < 0.35 ? 1 + rnd.nextInt(2) : 0;
                int fdScored = fdTaken > 0 ? rnd.nextInt(fdTaken + 1) : 0;
                lineup.add(new LineupPlayer(PLAYER_ID, TEAMS[0][1], home, "10", "Tomás Ejemplo", rnd.nextBoolean(),
                        goals, yellow, blue, red, penScored, penTaken, fdScored, fdTaken, "Ejemplo"));
                for (int g = 0; g < goals; g++) {
                    ev.add(event(rnd, EventType.GOAL, base));
                }
                // Alguna tarjeta sin evento en el acta, para mostrar la fila "sin minuto registrado".
                if (yellow == 1 && rnd.nextDouble() < 0.7) {
                    ev.add(event(rnd, EventType.YELLOW_CARD, base));
                }
                if (blue == 1) {
                    ev.add(event(rnd, EventType.BLUE_CARD, base));
                }
                if (red == 1) {
                    ev.add(event(rnd, EventType.RED_CARD, base));
                }
            }
            // Goles del rival y de los compañeros, con minuto (para "goles con uno menos").
            for (int g = 0; g < against; g++) {
                ev.add(new MatchEvent(1 + rnd.nextInt(2), rnd.nextInt(PERIOD_MINUTES), rnd.nextInt(60), EventType.GOAL,
                        base + opponent, opponent + 1, TEAMS[opponent][0], "", 0));
            }
            // Cuatro compañeros de campo: se reparten los goles que no hizo él, con algunas tarjetas y pelota parada
            // (son la referencia de sus fortalezas y debilidades).
            int teammates = own - lineup.stream().filter(p -> p.playerId() == PLAYER_ID).mapToInt(LineupPlayer::goals).sum();
            int[] mateGoals = new int[MATES.length];
            for (int g = 0; g < teammates; g++) {
                int k = rnd.nextInt(MATES.length);
                mateGoals[k]++;
                ev.add(new MatchEvent(1 + rnd.nextInt(2), rnd.nextInt(PERIOD_MINUTES), rnd.nextInt(60), EventType.GOAL,
                        base, CLUB_ID, "CEJ", "", 3 + k));
            }
            for (int k = 0; k < MATES.length; k++) {
                int penTaken = rnd.nextDouble() < 0.1 ? 1 : 0;
                int fdTaken = rnd.nextDouble() < 0.15 ? 1 : 0;
                lineup.add(new LineupPlayer(3 + k, TEAMS[0][1], home, String.valueOf(4 + k), MATES[k], k < 3,
                        mateGoals[k], rnd.nextDouble() < 0.12 ? 1 : 0, rnd.nextDouble() < 0.15 ? 1 : 0, 0,
                        penTaken == 1 && rnd.nextBoolean() ? 1 : 0, penTaken, fdTaken == 1 && rnd.nextBoolean() ? 1 : 0,
                        fdTaken, ""));
            }
            sheets.put(idp, lineup);
            events.put(idp, ev);
        }
        return new TeamInput(comp, label, base, matches, sheets, events);
    }

    private static MatchEvent event(Random rnd, EventType type, int teamId) {
        return new MatchEvent(1 + rnd.nextInt(2), rnd.nextInt(PERIOD_MINUTES), rnd.nextInt(60), type, teamId, CLUB_ID,
                "CEJ", "Tomás Ejemplo", PLAYER_ID);
    }
}
