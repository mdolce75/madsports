package com.fppstats.player;

import com.fppstats.stats.ReportData;

import java.time.LocalDate;
import java.util.List;

/**
 * Informe de un jugador: partidos, goles y tarjetas por categoría en la que jugó, y totales. Todo sale de las actas
 * (planilla de jugadores de cada partido): un partido cuenta si el jugador figura en la planilla de su equipo.
 *
 * @param playerName nombre del jugador en la FPP
 * @param fullName   nombre y apellido del usuario (como se registró)
 */
public record PlayerReport(int playerId, String playerName, String fullName, ReportData.ClubInfo club, int year,
                           String period, LocalDate generatedOn, List<Line> categories, Line total,
                           List<MatchRow> matches, List<String> notes) {

    public boolean hasData() {
        return total.played() > 0;
    }

    /** @param withoutSheet partidos jugados por el equipo sin acta publicada (no se sabe si jugó) */
    public record Line(String category, int played, int won, int drawn, int lost, int goals,
                       int yellowCards, int blueCards, int redCards, int withoutSheet) {

        static final Line EMPTY = new Line("Total", 0, 0, 0, 0, 0, 0, 0, 0, 0);

        public int cards() {
            return yellowCards + blueCards + redCards;
        }

        public double goalsPerGame() {
            return per(goals);
        }

        public double cardsPerGame() {
            return per(cards());
        }

        private double per(int value) {
            return played == 0 ? 0 : (double) value / played;
        }

        Line plus(Line o, String name) {
            return new Line(name, played + o.played, won + o.won, drawn + o.drawn, lost + o.lost, goals + o.goals,
                    yellowCards + o.yellowCards, blueCards + o.blueCards, redCards + o.redCards,
                    withoutSheet + o.withoutSheet);
        }
    }

    /** Un partido del jugador, según el acta. {@code result}: G, E o P. */
    public record MatchRow(LocalDate date, String category, String torneo, String rival, boolean home,
                           int goalsFor, int goalsAgainst, String result, boolean starter, int goals,
                           int yellowCards, int blueCards, int redCards) {
    }
}
