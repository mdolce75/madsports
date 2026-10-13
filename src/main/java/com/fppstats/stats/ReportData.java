package com.fppstats.stats;

import java.time.LocalDate;
import java.util.List;

/**
 * Todas las cifras del informe. Las calcula {@link StatsEngine} en código; el agente de IA solo
 * las lee (vía herramientas) y escribe el análisis, nunca produce números propios.
 */
public record ReportData(ClubInfo club, String category, String torneo, LocalDate generatedOn,
                         List<Source> sources, List<TeamReport> teams, LeagueContext league) {

    public record ClubInfo(int id, String code, String name) {
    }

    public record Source(String competition, String url) {
    }

    public record TeamReport(String code, String name, List<StandingSnapshot> standings,
                             TeamRecord total, TeamRecord home, TeamRecord away, List<String> form,
                             List<MatchLine> results, List<MatchLine> upcoming,
                             MatchLine biggestWin, MatchLine worstLoss,
                             List<PlayerLine> players, SetPieces setPieces, Discipline discipline,
                             List<RivalLine> rivals, List<TorneoLine> byTorneo) {
    }

    /**
     * Fila de la tabla tal como la publica la FPP.
     *
     * @param publishedOn fecha de generación de la tabla en SIDGAD (puede ser null)
     * @param outdated    true si la tabla tiene menos PJ que los partidos jugados según el calendario
     */
    public record StandingSnapshot(String competition, String group, int position, int groupSize,
                                   int points, int played, int goalsFor, int goalsAgainst, int goalDiff,
                                   LocalDate publishedOn, boolean outdated) {
    }

    public record TeamRecord(int played, int won, int drawn, int lost, int goalsFor, int goalsAgainst) {

        public static final TeamRecord EMPTY = new TeamRecord(0, 0, 0, 0, 0, 0);

        public int points() {
            return won * 3 + drawn;
        }

        public int goalDiff() {
            return goalsFor - goalsAgainst;
        }

        public double goalsForPerGame() {
            return played == 0 ? 0 : (double) goalsFor / played;
        }

        public double goalsAgainstPerGame() {
            return played == 0 ? 0 : (double) goalsAgainst / played;
        }

        /** Porcentaje de puntos obtenidos sobre los posibles. */
        public double effectiveness() {
            return played == 0 ? 0 : 100.0 * points() / (played * 3);
        }

        TeamRecord plus(int gf, int ga) {
            return new TeamRecord(played + 1, won + (gf > ga ? 1 : 0), drawn + (gf == ga ? 1 : 0),
                    lost + (gf < ga ? 1 : 0), goalsFor + gf, goalsAgainst + ga);
        }
    }

    /** Un partido visto desde el equipo del informe. {@code outcome}: G, E, P o null si no se jugó. */
    public record MatchLine(String torneo, int round, LocalDate date, String time, boolean home, String rival,
                            Integer goalsFor, Integer goalsAgainst, String outcome, String venue) {

        public String score() {
            return goalsFor == null ? "-" : goalsFor + " - " + goalsAgainst;
        }
    }

    public record PlayerLine(int playerId, String name, int games, int goals, int assists,
                             double goalsPerGame, double goalShare,
                             int penaltiesScored, int penaltiesTaken, int directFreeScored, int directFreeTaken,
                             int blueCards, int redCards) {
    }

    public record SetPieces(int directFreeScored, int directFreeTaken, int penaltiesScored, int penaltiesTaken) {

        public double directFreePct() {
            return pct(directFreeScored, directFreeTaken);
        }

        public double penaltyPct() {
            return pct(penaltiesScored, penaltiesTaken);
        }
    }

    public record Discipline(int blueCards, int redCards, double cardsPerGame) {
    }

    public record RivalLine(String rival, int played, int won, int drawn, int lost, int goalsFor, int goalsAgainst) {
    }

    public record TorneoLine(String torneo, TeamRecord record) {
    }

    /** Promedios de todas las competiciones incluidas en el informe (todos los equipos). */
    public record LeagueContext(int teams, int matchesPlayed, double goalsPerTeamGame, double top3GoalsForPerGame,
                                double directFreePct, double penaltyPct, double cardsPerTeamGame) {
    }

    static double pct(int part, int total) {
        return total == 0 ? 0 : 100.0 * part / total;
    }
}
