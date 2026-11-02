package com.madsports.compare;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.madsports.stats.ReportData.ClubInfo;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.ReportData.TeamReport;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Comparativo de dos equipos de la misma categoría en un período (un torneo, los últimos 5 partidos o el año): sus
 * números lado a lado, sus tarjetas con el marcador del momento y los enfrentamientos entre los dos.
 *
 * @param period        el período elegido: "Apertura", "Últimos 5 partidos", "Año 2026"
 * @param lastMatches   true si es el de los últimos {@link #LAST_MATCHES} partidos
 * @param playerTops    ¿van los top 3 por jugador? En los últimos 5, solo si se pudieron recortar a esos partidos
 *                      (con las fichas de temporada de los jugadores)
 * @param summary       el resumen escrito (quién llega mejor y por qué), un renglón por idea
 * @param notes         aclaraciones (p. ej. que juegan en divisiones distintas)
 * @param notPublished  lo que la fuente no publicó, de los dos equipos
 */
public record Comparison(String federation, int year, String category, String period, boolean lastMatches,
                         boolean playerTops, LocalDate generatedOn, Side a, Side b, List<HeadToHead> headToHead,
                         List<String> summary, List<String> notes, List<String> notPublished,
                         java.time.Instant dataAsOf) {

    public Comparison(String federation, int year, String category, String period, boolean lastMatches,
                      boolean playerTops, LocalDate generatedOn, Side a, Side b, List<HeadToHead> headToHead,
                      List<String> summary, List<String> notes, List<String> notPublished) {
        this(federation, year, category, period, lastMatches, playerTops, generatedOn, a, b, headToHead, summary,
                notes, notPublished, null);
    }

    /** El mismo comparativo con la fecha de los datos de SIDGAD que usa (null si no se sabe). */
    public Comparison withDataAsOf(java.time.Instant at) {
        return new Comparison(federation, year, category, period, lastMatches, playerTops, generatedOn, a, b,
                headToHead, summary, notes, notPublished, at);
    }

    /**
     * Para marcar quién va adelante en una métrica: "ahead" si {@code mine} es mejor que {@code other} (más es mejor
     * si {@code higherIsBetter}, si no menos); "" si es peor o empatan.
     */
    public String ahead(double mine, double other, boolean higherIsBetter) {
        return (higherIsBetter ? mine > other : mine < other) ? "ahead" : "";
    }

    /** El período con su año: "Apertura 2026", "Últimos 5 partidos 2026", "Año 2026". */
    public String periodTitle() {
        return period.startsWith("Año") ? period : period + " " + year;
    }

    /** "Enfrentamientos del Apertura", o "del año" si el período no es un torneo. */
    public String headToHeadTitle() {
        return "Enfrentamientos " + (lastMatches || period.startsWith("Año") ? "del año" : "del " + period);
    }

    /** Últimos partidos con que se mide cómo llega cada equipo. */
    public static final int LAST_MATCHES = 5;

    /** Un equipo del comparativo, en el período elegido ({@link Comparison#period}). */
    public record Side(ClubInfo club, String teamKey, String name, View view) {
    }

    /** El equipo en el período: el informe de equipo y sus tarjetas una por una. */
    public record View(TeamReport report, List<CardLine> cards) {

        /** Los 3 que más goles hicieron (a igualdad, el de menos partidos). */
        @JsonIgnore
        public List<PlayerLine> topScorers() {
            return report.players().stream().filter(p -> p.goals() > 0)
                    .sorted(Comparator.comparingInt(PlayerLine::goals).reversed().thenComparingInt(PlayerLine::games))
                    .limit(3).toList();
        }

        /** Los 3 que más penales convirtieron (a igualdad, el de mejor porcentaje). */
        @JsonIgnore
        public List<PlayerLine> topPenalties() {
            return top(PlayerLine::penaltiesScored, PlayerLine::penaltiesTaken);
        }

        /** Los 3 que más libres directos convirtieron. */
        @JsonIgnore
        public List<PlayerLine> topFreeHits() {
            return top(PlayerLine::directFreeScored, PlayerLine::directFreeTaken);
        }

        private List<PlayerLine> top(ToIntFunction<PlayerLine> scored, ToIntFunction<PlayerLine> taken) {
            return report.squad().stream().filter(p -> taken.applyAsInt(p) > 0)
                    .sorted(Comparator.comparingInt(scored).reversed()
                            .thenComparing(p -> -(double) scored.applyAsInt(p) / taken.applyAsInt(p)))
                    .limit(3).toList();
        }

        /** Los 3 con más tarjetas (a igualdad, más rojas y después más azules). */
        @JsonIgnore
        public List<PlayerLine> topDiscipline() {
            return report.squad().stream().filter(p -> p.cards() > 0)
                    .sorted(Comparator.comparingInt(PlayerLine::cards).reversed()
                            .thenComparing(Comparator.comparingInt(PlayerLine::redCards).reversed())
                            .thenComparing(Comparator.comparingInt(PlayerLine::blueCards).reversed()))
                    .limit(3).toList();
        }

        /** Tarjetas de jugadores en las actas (sin el cuerpo técnico), por color: amarillas, azules, rojas. */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public int[] cardColors() {
            int[] out = new int[3];
            cards.stream().filter(c -> !c.staff()).forEach(c -> out[switch (c.card()) {
                case "Amarilla" -> 0;
                case "Azul" -> 1;
                default -> 2;
            }]++);
            return out;
        }

        /** Las tarjetas del cuerpo técnico (van aparte de los conteos). */
        @JsonIgnore
        public List<CardLine> staffCards() {
            return cards.stream().filter(CardLine::staff).toList();
        }

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
