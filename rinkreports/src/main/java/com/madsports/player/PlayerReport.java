package com.madsports.player;

import com.madsports.sidgad.model.Federation;

import com.madsports.stats.ReportData;

import java.time.LocalDate;
import java.util.List;

/**
 * Informe de un jugador: partidos, goles, tarjetas (amarilla, azul y roja por separado), penales y libres directos por
 * categoría en la que jugó y en total, y goles y tarjetas por tramos de 5 minutos. Todo sale de las actas: un partido
 * cuenta si el jugador figura en la planilla de su equipo; el minuto, de los eventos del acta.
 *
 * @param playerName nombre del jugador en la FPP
 * @param fullName   nombre y apellido del usuario (como se registró)
 */
public record PlayerReport(int playerId, String playerName, String fullName, ReportData.ClubInfo club, int year,
                           String period, LocalDate generatedOn, List<Line> categories, Line total,
                           Minutes minutes, ReportData.ShortHanded shortHanded, List<MatchRow> matches,
                           List<String> notes, List<CategoryBlock> blocks, List<ReportData.Indicator> indicators,
                           Federation federation) {

    public PlayerReport(int playerId, String playerName, String fullName, ReportData.ClubInfo club, int year,
                        String period, LocalDate generatedOn, List<Line> categories, Line total, Minutes minutes,
                        ReportData.ShortHanded shortHanded, List<MatchRow> matches, List<String> notes,
                        List<CategoryBlock> blocks, List<ReportData.Indicator> indicators) {
        this(playerId, playerName, fullName, club, year, period, generatedOn, categories, total, minutes, shortHanded,
                matches, notes, blocks, indicators, Federation.FPP);
    }

    /** Sin bloques por categoría ni fortalezas (informes armados a mano en los tests). */
    public PlayerReport(int playerId, String playerName, String fullName, ReportData.ClubInfo club, int year,
                        String period, LocalDate generatedOn, List<Line> categories, Line total, Minutes minutes,
                        ReportData.ShortHanded shortHanded, List<MatchRow> matches, List<String> notes) {
        this(playerId, playerName, fullName, club, year, period, generatedOn, categories, total, minutes, shortHanded,
                matches, notes, List.of(), List.of());
    }

    public PlayerReport(int playerId, String playerName, String fullName, ReportData.ClubInfo club, int year,
                        String period, LocalDate generatedOn, List<Line> categories, Line total, Minutes minutes,
                        ReportData.ShortHanded shortHanded, List<MatchRow> matches, List<String> notes,
                        List<CategoryBlock> blocks) {
        this(playerId, playerName, fullName, club, year, period, generatedOn, categories, total, minutes, shortHanded,
                matches, notes, blocks, List.of());
    }

    public List<ReportData.Indicator> strengths() {
        return indicators.stream().filter(i -> i.verdict() == ReportData.Verdict.STRENGTH).toList();
    }

    public List<ReportData.Indicator> weaknesses() {
        return indicators.stream().filter(i -> i.verdict() == ReportData.Verdict.WEAKNESS).toList();
    }

    /**
     * Todas las estadísticas de una categoría: totales, goles y tarjetas por tramo, goles con uno menos por sus
     * sanciones, sus partidos y sus fortalezas y debilidades (contra sus compañeros en esa categoría).
     */
    public record CategoryBlock(Line line, Minutes minutes, ReportData.ShortHanded shortHanded,
                                List<MatchRow> matches, List<ReportData.Indicator> indicators, LeagueRank leagueRank) {

        public CategoryBlock(Line line, Minutes minutes, ReportData.ShortHanded shortHanded, List<MatchRow> matches,
                             List<ReportData.Indicator> indicators) {
            this(line, minutes, shortHanded, matches, indicators, null);
        }

        public CategoryBlock(Line line, Minutes minutes, ReportData.ShortHanded shortHanded, List<MatchRow> matches) {
            this(line, minutes, shortHanded, matches, List.of(), null);
        }

        CategoryBlock withLeagueRank(LeagueRank rank) {
            return new CategoryBlock(line, minutes, shortHanded, matches, indicators, rank);
        }
    }

    /**
     * Su lugar en la tabla de goleadores de la categoría (los torneos del período sumados), según las estadísticas
     * que publica SIDGAD.
     *
     * @param position    puesto entre todos los jugadores de la categoría (empatados comparten puesto)
     * @param players     jugadores de la categoría en la tabla
     * @param goals       sus goles en esa tabla
     * @param leaderGoals los goles del goleador de la categoría
     */
    public record LeagueRank(int position, int players, int goals, int leaderGoals) {

        /** En qué porcentaje de arriba de la tabla está (1 a 100). */
        public int topPercent() {
            return players == 0 ? 100 : Math.max(1, (int) Math.ceil(100.0 * position / players));
        }
    }

    /** El mismo informe, con el lugar en la liga de cada categoría (por nombre de categoría; las que falten, sin él). */
    public PlayerReport withLeagueRanks(java.util.Map<String, LeagueRank> ranks) {
        return new PlayerReport(playerId, playerName, fullName, club, year, period, generatedOn, categories, total,
                minutes, shortHanded, matches, notes,
                blocks.stream().map(b -> b.withLeagueRank(ranks.get(b.line().category()))).toList(),
                indicators, federation);
    }

    public boolean hasData() {
        return total.played() > 0;
    }

    /**
     * @param withoutSheet partidos jugados por el equipo sin acta publicada (no se sabe si jugó)
     * @param teamGoals    goles de su equipo en los partidos que jugó (para la incidencia de sus goles)
     * @param teamCards    tarjetas de su equipo en los partidos con acta que jugó (las de los sin acta no se conocen)
     * @param sheetCards   sus tarjetas en esos mismos partidos con acta
     */
    public record Line(String category, int played, int won, int drawn, int lost, int goals,
                       int yellowCards, int blueCards, int redCards,
                       int penaltiesScored, int penaltiesTaken, int directFreeScored, int directFreeTaken,
                       int withoutSheet, int teamGoals, int teamCards, int sheetCards) {

        static final Line EMPTY = new Line("Total", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        /** Incidencia de sus goles: qué parte de los goles de su equipo en sus partidos hizo él (0 a 100). */
        public double goalShare() {
            return teamGoals == 0 ? 0 : 100.0 * goals / teamGoals;
        }

        /**
         * Incidencia de sus tarjetas: qué parte de las tarjetas de su equipo recibió él (0 a 100), en los partidos con
         * acta (de los sin acta, la ficha de temporada trae solo las suyas, no las del equipo).
         */
        public double cardShare() {
            return teamCards == 0 ? 0 : 100.0 * sheetCards / teamCards;
        }

        public int cards() {
            return yellowCards + blueCards + redCards;
        }

        public double goalsPerGame() {
            return per(goals);
        }

        public double cardsPerGame() {
            return per(cards());
        }

        public double yellowPerGame() {
            return per(yellowCards);
        }

        public double bluePerGame() {
            return per(blueCards);
        }

        public double redPerGame() {
            return per(redCards);
        }

        /** Efectividad de penales (0 a 1); 0 si no tiró. */
        public double penaltyRate() {
            return penaltiesTaken == 0 ? 0 : (double) penaltiesScored / penaltiesTaken;
        }

        public double directFreeRate() {
            return directFreeTaken == 0 ? 0 : (double) directFreeScored / directFreeTaken;
        }

        public boolean hasSetPieces() {
            return penaltiesTaken + directFreeTaken > 0;
        }

        private double per(int value) {
            return played == 0 ? 0 : (double) value / played;
        }

        Line plus(Line o, String name) {
            return new Line(name, played + o.played, won + o.won, drawn + o.drawn, lost + o.lost, goals + o.goals,
                    yellowCards + o.yellowCards, blueCards + o.blueCards, redCards + o.redCards,
                    penaltiesScored + o.penaltiesScored, penaltiesTaken + o.penaltiesTaken,
                    directFreeScored + o.directFreeScored, directFreeTaken + o.directFreeTaken,
                    withoutSheet + o.withoutSheet, teamGoals + o.teamGoals, teamCards + o.teamCards,
                    sheetCards + o.sheetCards);
        }
    }

    /**
     * Goles y tarjetas del jugador por tramo de 5 minutos.
     *
     * @param periodMinutes duración de cada tiempo (la mayor entre sus categorías: 20' hasta Juvenil, 25' desde Juniors)
     * @param unrecorded    goles y tarjetas sin minuto: el acta no trae el evento, o la tarjeta es de fuera del juego
     */
    public record Minutes(int periodMinutes, List<Bucket> buckets, Bucket unrecorded) {

        public boolean hasData() {
            return buckets.stream().anyMatch(b -> b.goals() + b.cards() > 0) || unrecorded.goals() + unrecorded.cards() > 0;
        }

        /**
         * Todos los goles y tarjetas: los de los tramos y los sin minuto registrado (de partidos sin acta o eventos sin
         * minuto). Es lo mismo que cuentan los totales del informe.
         */
        public Bucket total() {
            List<Bucket> all = new java.util.ArrayList<>(buckets);
            all.add(unrecorded);
            return sum("Total", all);
        }

        public List<Bucket> byHalf() {
            int half = buckets.size() / 2;
            return List.of(sum("1er tiempo", buckets.subList(0, half)), sum("2do tiempo", buckets.subList(half, buckets.size())));
        }

        private static Bucket sum(String label, List<Bucket> list) {
            return new Bucket(label, list.stream().mapToInt(Bucket::goals).sum(),
                    list.stream().mapToInt(Bucket::yellowCards).sum(), list.stream().mapToInt(Bucket::blueCards).sum(),
                    list.stream().mapToInt(Bucket::redCards).sum());
        }
    }

    /** Un tramo ("1T 0-5'"): goles y tarjetas por color. */
    public record Bucket(String label, int goals, int yellowCards, int blueCards, int redCards) {

        public int cards() {
            return yellowCards + blueCards + redCards;
        }
    }

    /**
     * Un partido del jugador, según el acta. {@code result}: G, E o P.
     *
     * @param fromSummary el partido no tiene acta: sale de su ficha de temporada (sin amarillas ni titularidad)
     */
    public record MatchRow(LocalDate date, String category, String tournament, String opponent, boolean home,
                           int goalsFor, int goalsAgainst, String result, boolean starter, int goals,
                           int yellowCards, int blueCards, int redCards, boolean fromSummary) {

        public MatchRow(LocalDate date, String category, String tournament, String opponent, boolean home,
                        int goalsFor, int goalsAgainst, String result, boolean starter, int goals,
                        int yellowCards, int blueCards, int redCards) {
            this(date, category, tournament, opponent, home, goalsFor, goalsAgainst, result, starter, goals,
                    yellowCards, blueCards, redCards, false);
        }
    }
}
