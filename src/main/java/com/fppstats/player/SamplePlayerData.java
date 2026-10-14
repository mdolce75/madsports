package com.fppstats.player;

import com.fppstats.report.ReportService.Scope;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Competition.Category;
import com.fppstats.sidgad.model.Competition.Torneo;
import com.fppstats.sidgad.model.Models.EventType;
import com.fppstats.sidgad.model.Models.LineupPlayer;
import com.fppstats.sidgad.model.Models.Match;
import com.fppstats.sidgad.model.Models.MatchEvent;
import com.fppstats.sidgad.model.Models.TeamRef;
import com.fppstats.stats.ReportData;

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

    private SamplePlayerData() {
    }

    /** @param logo escudo del club ficticio (data URI) */
    public static PlayerReport build(LocalDate today, String logo) {
        Random rnd = new Random(2026);
        Category juniors = new Category("JUNIORS", "MASCULINO");
        Category primera = new Category("PRIMERA", "MASCULINO");
        List<PlayerReportService.TeamInput> inputs = List.of(
                team(9101, Torneo.APERTURA, juniors, today.minusWeeks(30), 9, 1.0, false, today, rnd),
                team(9102, Torneo.CLAUSURA, juniors, today.minusWeeks(5), 5, 1.0, false, today, rnd),
                team(9201, Torneo.APERTURA, primera, today.minusWeeks(30).plusDays(1), 9, 0.55, true, today, rnd),
                team(9202, Torneo.CLAUSURA, primera, today.minusWeeks(5).plusDays(1), 5, 0.6, false, today, rnd));
        PlayerReportService.Query q = new PlayerReportService.Query(PLAYER_ID, "Tomás Ejemplo", "Tomás Ejemplo",
                CLUB_ID, today.getYear(), Scope.TEMPORADA, Eligibility.levels("JUNIORS"), "MASCULINO");
        return PlayerReportService.assemble(q, new ReportData.ClubInfo(CLUB_ID, "CEJ", "CLUB EJEMPLO", logo), today,
                LEVELS, inputs);
    }

    /**
     * Un equipo del club en un torneo: {@code rounds} fechas jugadas, el jugador citado con probabilidad
     * {@code presence}; con {@code missingSheet}, a la última fecha le falta el acta (como pasa en SIDGAD).
     */
    private static PlayerReportService.TeamInput team(int id, Torneo torneo, Category category, LocalDate first,
                                                      int rounds, double presence, boolean missingSheet,
                                                      LocalDate today, Random rnd) {
        int base = id * 100;
        List<TeamRef> teams = new ArrayList<>();
        for (int i = 0; i < TEAMS.length; i++) {
            teams.add(new TeamRef(base + i, i + 1, TEAMS[i][0], TEAMS[i][1]));
        }
        Competition comp = new Competition(id, "TORNEO " + torneo.name() + " " + category.level() + " MASCULINO (EJEMPLO)",
                3, 40, torneo, category, 1, teams, PERIOD_MINUTES);
        String label = category.levelLabel() + " " + category.branchLabel();

        List<Match> matches = new ArrayList<>();
        Map<Integer, List<LineupPlayer>> sheets = new LinkedHashMap<>();
        Map<Integer, List<MatchEvent>> events = new LinkedHashMap<>();
        for (int r = 0; r < rounds; r++) {
            LocalDate date = first.plusWeeks(r);
            if (!date.isBefore(today)) {
                break;
            }
            int rival = 1 + (r % (TEAMS.length - 1));
            boolean home = r % 2 == 0;
            int own = rnd.nextInt(6);
            int against = rnd.nextInt(6);
            int idp = base + 50 + r;
            matches.add(new Match(idp, r + 1, date, LocalTime.of(18, 0), "", home ? base : base + rival,
                    home ? CLUB_ID : rival + 1, home ? TEAMS[0][1] : TEAMS[rival][1], home ? base + rival : base,
                    home ? rival + 1 : CLUB_ID, home ? TEAMS[rival][1] : TEAMS[0][1],
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
            sheets.put(idp, lineup);
            events.put(idp, ev);
        }
        return new PlayerReportService.TeamInput(comp, label, base, matches, sheets, events);
    }

    private static MatchEvent event(Random rnd, EventType type, int teamId) {
        return new MatchEvent(1 + rnd.nextInt(2), rnd.nextInt(PERIOD_MINUTES), rnd.nextInt(60), type, teamId, CLUB_ID,
                "CEJ", "Tomás Ejemplo", PLAYER_ID);
    }
}
