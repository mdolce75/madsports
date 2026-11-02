package com.madsports.compare;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.madsports.stats.ReportData.ClubInfo;
import com.madsports.stats.ReportData.TeamReport;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Comparativo de dos equipos de la misma categoría: cada uno en el torneo actual y en el año, cómo llega (sus últimos 5
 * partidos), sus tarjetas con el marcador del momento y los enfrentamientos del año entre los dos.
 *
 * @param summary       el resumen escrito (quién llega mejor y por qué), un renglón por idea
 * @param notes         aclaraciones (p. ej. que juegan en divisiones distintas)
 * @param notPublished  lo que la fuente no publicó, de los dos equipos
 */
public record Comparison(String federation, int year, String category, LocalDate generatedOn, Side a, Side b,
                         List<HeadToHead> headToHead, List<String> summary, List<String> notes,
                         List<String> notPublished) {

    /** Últimos partidos con que se mide cómo llega cada equipo. */
    public static final int LAST_MATCHES = 5;

    /**
     * Un equipo del comparativo.
     *
     * @param currentTournament el torneo de su último partido jugado ("Clausura"); el año entero si no jugó
     * @param last5             sus últimos {@link #LAST_MATCHES} partidos (récord, resultados, goles por tramo)
     */
    public record Side(ClubInfo club, String teamKey, String name, String currentTournament, View current, View season,
                       TeamReport last5) {
    }

    /** El equipo en un alcance (torneo actual o año): el informe de equipo y sus tarjetas una por una. */
    public record View(TeamReport report, List<CardLine> cards) {

        /** Tarjetas de jugadores (sin el cuerpo técnico) por estado del partido: ganando, empatando, perdiendo. */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int[] cardStates() {
            int[] out = new int[3];
            cards.stream().filter(c -> !c.staff()).forEach(c -> out[c.state().ordinal()]++);
            return out;
        }
    }

    /** Cómo iba el partido para el equipo cuando le sacaron la tarjeta. */
    public enum State {
        AHEAD("ganando"), LEVEL("empatando"), BEHIND("perdiendo");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Una tarjeta del equipo, con el marcador (propio y del rival) en ese momento.
     *
     * @param staff  true si no figura en las planillas de jugadores: es del cuerpo técnico (va a la lista, no a los
     *               conteos de jugadores)
     * @param card   "Amarilla", "Azul" o "Roja"
     */
    public record CardLine(String tournament, LocalDate date, String opponent, String player, boolean staff, String card,
                           int period, int minute, int second, int goalsFor, int goalsAgainst) {

        /** "2T 03:23". */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String minuteLabel() {
            return "%dT %02d:%02d".formatted(period, minute, second);
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String score() {
            return goalsFor + "-" + goalsAgainst;
        }

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public State state() {
            return goalsFor > goalsAgainst ? State.AHEAD : goalsFor == goalsAgainst ? State.LEVEL : State.BEHIND;
        }
    }

    /**
     * Un partido entre los dos (jugado o por jugar), con los goleadores si hay acta.
     *
     * @param homeIsA true si el local es el equipo A del comparativo
     */
    public record HeadToHead(String tournament, int round, LocalDate date, LocalTime time, String venue, String home,
                             String away, boolean homeIsA, Integer homeGoals, Integer awayGoals, List<Scorer> scorers) {

        public boolean played() {
            return homeGoals != null && awayGoals != null;
        }

        /** Goles del equipo A (null si no se jugó). */
        public Integer goalsA() {
            return homeIsA ? homeGoals : awayGoals;
        }

        public Integer goalsB() {
            return homeIsA ? awayGoals : homeGoals;
        }
    }

    /** Goles de un jugador en un partido; {@code home} dice de qué equipo es. */
    public record Scorer(boolean home, String player, int goals) {
    }
}
