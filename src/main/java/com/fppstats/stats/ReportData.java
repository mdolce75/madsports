package com.fppstats.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * Todas las cifras del informe. Las calcula {@link StatsEngine} en código; la IA solo las lee y escribe
 * el análisis, nunca produce números propios.
 * Los métodos derivados con {@code @JsonProperty} (puntos, efectividad, promedios, porcentajes, minutos por
 * partido) también van al JSON exportado, para que quien lo lea no tenga que hacer cuentas.
 */
public record ReportData(ClubInfo club, String category, String torneo, LocalDate generatedOn,
                         int lastMatches, List<Source> sources, List<TeamReport> teams, LeagueContext league,
                         List<String> notPublished) {

    /** Copia con la lista de datos que el sitio no publicó (respuestas 404 de SIDGAD). */
    public ReportData withNotPublished(List<String> items) {
        return new ReportData(club, category, torneo, generatedOn, lastMatches, sources, teams, league, List.copyOf(items));
    }

    /** True si el informe se limita a los últimos N partidos (jugadores y pelota parada siguen siendo del torneo). */
    public boolean partial() {
        return lastMatches > 0;
    }

    public record ClubInfo(int id, String code, String name) {
    }

    public record Source(String competition, String url) {
    }

    public record TeamReport(String code, String name, List<StandingSnapshot> standings,
                             TeamRecord total, TeamRecord home, TeamRecord away, List<String> form,
                             List<MatchLine> results, List<MatchLine> upcoming,
                             MatchLine biggestWin, MatchLine worstLoss,
                             List<PlayerLine> players, SetPieces setPieces, Discipline discipline,
                             List<RivalLine> rivals, List<TorneoLine> byTorneo, TimeProfile timeProfile,
                             List<Indicator> indicators) {
    }

    /** Resultado de comparar un indicador del equipo con su referencia. */
    public enum Verdict {
        FORTALEZA("Fortaleza"), DEBILIDAD("Debilidad"), EN_LINEA("En línea"), MUESTRA_CHICA("Muestra chica");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Cómo mostrar el valor: goles o tarjetas por partido, porcentaje, minutos (mm:ss) o diferencia con signo. */
    public enum Unit { PER_GAME, PERCENT, MINUTES, DIFF }

    /**
     * Un aspecto del juego evaluado contra una referencia (análisis de fortalezas y debilidades).
     *
     * @param referenceLabel qué es la referencia ("Liga", "Temporada", "Con uno más", "Umbral", "Equilibrio")
     * @param explanation    por qué se clasifica así, en una frase
     */
    public record Indicator(String area, String metric, double value, String referenceLabel, double reference,
                            Unit unit, Verdict verdict, String explanation) {
    }

    /**
     * Goles y tarjetas por tramos de 5 minutos, según las actas de los partidos jugados.
     *
     * @param matchesWithSheet partidos con acta publicada (solo esos se cuentan)
     * @param goalMismatches   actas cuyos goles no coinciden con el resultado oficial
     */
    /**
     * @param unrecorded goles sin minuto conocido: los de partidos sin ficha y los que faltan en fichas incompletas.
     *                   Con esta fila, los goles de la tabla suman lo mismo que el récord. Sus tarjetas no se conocen.
     */
    /**
     * @param shortHandedSeconds tiempo total con jugadores de menos por sanciones (azul 2', roja 4'), en los
     *                           partidos con ficha; las sanciones superpuestas se suman
     * @param powerPlaySeconds   lo mismo con las sanciones del rival (tiempo con jugadores de más)
     */
    public record TimeProfile(int periodMinutes, int matchesPlayed, int matchesWithSheet, int goalMismatches,
                              List<TimeBucket> buckets, List<TimeBucket> byPeriod, TimeBucket unrecorded,
                              int shortHandedSeconds, int powerPlaySeconds) {

        /** Minutos por partido con uno menos (promedio sobre los partidos con ficha). */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public double shortHandedMinutesPerGame() {
            return matchesWithSheet == 0 ? 0 : shortHandedSeconds / 60.0 / matchesWithSheet;
        }

        /** Minutos por partido con uno más (promedio sobre los partidos con ficha). */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public double powerPlayMinutesPerGame() {
            return matchesWithSheet == 0 ? 0 : powerPlaySeconds / 60.0 / matchesWithSheet;
        }

        public boolean hasUnrecordedGoals() {
            return unrecorded.goalsFor() + unrecorded.goalsAgainst() > 0;
        }

        /** Tarjetas cargadas en la ficha fuera del juego (p. ej. después del partido): sin minuto ni inferioridad. */
        public boolean hasUnrecordedCards() {
            return unrecorded.cardsOwn() + unrecorded.cardsRival() > 0;
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int totalGoalsFor() {
            return byPeriod.stream().mapToInt(TimeBucket::goalsFor).sum() + unrecorded.goalsFor();
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int totalGoalsAgainst() {
            return byPeriod.stream().mapToInt(TimeBucket::goalsAgainst).sum() + unrecorded.goalsAgainst();
        }

        public boolean hasData() {
            return matchesWithSheet > 0;
        }

        /** Máximo de goles (a favor o en contra) en un tramo, para escalar los gráficos. */
        public int maxGoalsInBucket() {
            return buckets.stream().mapToInt(b -> Math.max(b.goalsFor(), b.goalsAgainst())).max().orElse(0);
        }
    }

    /** Un tramo (p. ej. "1T 5-10'") o un período completo. "Propias" = del equipo; "rival" = sacadas a los rivales. */
    public record TimeBucket(String label, int period, int fromMinute, int toMinute,
                             int goalsFor, int goalsAgainst,
                             int yellowOwn, int blueOwn, int redOwn,
                             int yellowRival, int blueRival, int redRival) {

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int cardsOwn() {
            return yellowOwn + blueOwn + redOwn;
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int cardsRival() {
            return yellowRival + blueRival + redRival;
        }
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

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int points() {
            return won * 3 + drawn;
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int goalDiff() {
            return goalsFor - goalsAgainst;
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public double goalsForPerGame() {
            return played == 0 ? 0 : (double) goalsFor / played;
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public double goalsAgainstPerGame() {
            return played == 0 ? 0 : (double) goalsAgainst / played;
        }

        /** Porcentaje de puntos obtenidos sobre los posibles. */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
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

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
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

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public double directFreePct() {
            return pct(directFreeScored, directFreeTaken);
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public double penaltyPct() {
            return pct(penaltiesScored, penaltiesTaken);
        }
    }

    /**
     * Tarjetas propias.
     *
     * @param yellowCards  solo se conocen desde las fichas ({@code fromSheets}); si no, -1
     * @param cardsPerGame azules + rojas por partido, comparable con {@link LeagueContext#cardsPerTeamGame()}
     * @param matches      partidos sobre los que se cuentan las tarjetas
     * @param fromSheets   true = contadas en las fichas de los partidos del recorte ("últimos N");
     *                     false = estadísticas por jugador del torneo completo
     */
    public record Discipline(int yellowCards, int blueCards, int redCards, double cardsPerGame,
                             int matches, boolean fromSheets) {

        public boolean yellowKnown() {
            return yellowCards >= 0;
        }
    }

    public record RivalLine(String rival, int played, int won, int drawn, int lost, int goalsFor, int goalsAgainst) {
    }

    public record TorneoLine(String torneo, TeamRecord record) {
    }

    /** Promedios de todas las competiciones incluidas en el informe (todos los equipos). */
    /** @param effectiveness % de puntos obtenidos por un equipo promedio (victoria 3, empate 1) */
    public record LeagueContext(int teams, int matchesPlayed, double goalsPerTeamGame, double top3GoalsForPerGame,
                                double directFreePct, double penaltyPct, double cardsPerTeamGame, double effectiveness) {
    }

    static double pct(int part, int total) {
        return total == 0 ? 0 : 100.0 * part / total;
    }
}
