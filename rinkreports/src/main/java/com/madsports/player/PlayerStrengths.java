package com.madsports.player;

import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.stats.ReportData;
import com.madsports.stats.ReportData.Indicator;
import com.madsports.stats.ReportData.Unit;
import com.madsports.stats.StrengthsAnalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fortalezas y debilidades del jugador, con el mismo criterio que el informe de equipo (±10% de la referencia es
 * "en línea"; con menos de 3 casos, "muestra chica"). La referencia son sus compañeros en los mismos partidos (todo
 * sale de las planillas de las actas); la efectividad del equipo se compara con los partidos en que no jugó, y los
 * goles recibidos durante sus sanciones con el resto del tiempo.
 */
final class PlayerStrengths {

    private static final String MATES = "Compañeros";

    private PlayerStrengths() {
    }

    /** Totales del jugador o de sus compañeros (en apariciones: un compañero en un partido cuenta una vez). */
    static final class Tally {
        int appearances;
        int starts;
        int goals;
        int cards;
        int blueRed;
        int penScored;
        int penTaken;
        int fdScored;
        int fdTaken;

        void add(LineupPlayer p) {
            appearances++;
            starts += p.starter() ? 1 : 0;
            goals += p.goals();
            cards += p.yellowCards() + p.blueCards() + p.redCards();
            blueRed += p.blueCards() + p.redCards();
            penScored += p.penaltiesScored();
            penTaken += p.penaltiesTaken();
            fdScored += p.directFreeScored();
            fdTaken += p.directFreeTaken();
        }

        double per(int value) {
            return appearances == 0 ? 0 : (double) value / appearances;
        }

        static double pct(int part, int total) {
            return total == 0 ? 0 : 100.0 * part / total;
        }
    }

    static List<Indicator> evaluate(int playerId, List<TeamInput> inputs, ReportData.ShortHanded shortHanded) {
        Tally me = new Tally();
        Tally mates = new Tally();
        int[] with = new int[3];      // ganados, empatados, perdidos cuando juega
        int[] without = new int[3];   // ... en los partidos con acta en que no jugó
        for (TeamInput in : inputs) {
            for (Match m : in.matches()) {
                List<LineupPlayer> sheet = in.sheets().getOrDefault(m.idp(), List.of());
                if (sheet.isEmpty()) {
                    continue;
                }
                Optional<LineupPlayer> mine = sheet.stream().filter(p -> p.playerId() == playerId).findFirst();
                boolean home = m.homeTeamId() == in.teamId();
                int gf = home ? m.homeGoals() : m.awayGoals();
                int ga = home ? m.awayGoals() : m.homeGoals();
                int result = gf > ga ? 0 : gf == ga ? 1 : 2;
                if (mine.isEmpty()) {
                    // Solo cuentan los equipos en que jugó alguna vez (no los de otras categorías del club).
                    if (playedFor(playerId, in)) {
                        without[result]++;
                    }
                    continue;
                }
                with[result]++;
                me.add(mine.get());
                sheet.stream().filter(p -> p.playerId() != playerId).forEach(mates::add);
            }
        }
        List<Indicator> out = new ArrayList<>();
        if (me.appearances == 0) {
            return out;
        }
        out.add(StrengthsAnalysis.relative("Goleo", "Goles por partido", me.per(me.goals), MATES,
                mates.per(mates.goals), Unit.PER_GAME, true, me.appearances, "partidos"));
        // Sin titulares en las planillas (gestiondepsj no los marca), no hay titularidad que comparar.
        if (me.starts + mates.starts > 0) {
            out.add(StrengthsAnalysis.relative("Titularidad", "Partidos en el cinco inicial",
                    Tally.pct(me.starts, me.appearances), MATES, Tally.pct(mates.starts, mates.appearances), Unit.PERCENT,
                    true, me.appearances, "partidos"));
        }
        out.add(StrengthsAnalysis.relative("Disciplina", "Tarjetas por partido", me.per(me.cards), MATES,
                mates.per(mates.cards), Unit.PER_GAME, false, me.appearances, "partidos"));
        out.add(StrengthsAnalysis.relative("Disciplina", "Azules y rojas por partido", me.per(me.blueRed), MATES,
                mates.per(mates.blueRed), Unit.PER_GAME, false, me.appearances, "partidos"));
        // Sin penales ni libres en el plantel (gestiondepsj no los distingue de los goles), no hay pelota parada.
        if (me.penTaken + mates.penTaken + me.fdTaken + mates.fdTaken > 0) {
            out.add(StrengthsAnalysis.relative("Pelota parada", "Penales convertidos", Tally.pct(me.penScored, me.penTaken),
                    MATES, Tally.pct(mates.penScored, mates.penTaken), Unit.PERCENT, true, me.penTaken, "intentos"));
            out.add(StrengthsAnalysis.relative("Pelota parada", "Libres directos convertidos",
                    Tally.pct(me.fdScored, me.fdTaken), MATES, Tally.pct(mates.fdScored, mates.fdTaken), Unit.PERCENT, true,
                    me.fdTaken, "intentos"));
        }
        int withoutPlayed = without[0] + without[1] + without[2];
        out.add(StrengthsAnalysis.relative("Resultados", "Efectividad del equipo cuando juega", effectiveness(with), "Sin jugar",
                effectiveness(without), Unit.PERCENT, true, withoutPlayed, "partidos que no jugó"));
        if (shortHanded.hasData()) {
            out.add(StrengthsAnalysis.relative("Inferioridad", "Goles recibidos cada 10' en sus sanciones",
                    shortHanded.againstPer10Short(), "Resto del tiempo", shortHanded.againstPer10Rest(),
                    Unit.PER_GAME, false, shortHanded.matches().size(), "partidos con sanción"));
        }
        return out;
    }

    /** ¿El jugador figura en alguna planilla de este equipo? */
    private static boolean playedFor(int playerId, TeamInput in) {
        return in.sheets().values().stream().anyMatch(s -> s.stream().anyMatch(p -> p.playerId() == playerId));
    }

    /** Puntos obtenidos sobre los posibles (ganado 3, empatado 1), en %. */
    static double effectiveness(int[] results) {
        int played = results[0] + results[1] + results[2];
        return played == 0 ? 0 : 100.0 * (3 * results[0] + results[1]) / (3 * played);
    }
}
