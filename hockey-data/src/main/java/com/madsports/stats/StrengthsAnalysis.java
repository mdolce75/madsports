package com.madsports.stats;

import com.madsports.stats.ReportData.Indicator;
import com.madsports.stats.ReportData.LeagueContext;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.ReportData.TeamRecord;
import com.madsports.stats.ReportData.TeamReport;
import com.madsports.stats.ReportData.TimeBucket;
import com.madsports.stats.ReportData.TimeProfile;
import com.madsports.stats.ReportData.Unit;
import com.madsports.stats.ReportData.Verdict;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Análisis de fortalezas y debilidades: cada aspecto del juego se compara con una referencia y se clasifica.
 * Es determinístico; el texto de la IA y el análisis por reglas se apoyan en esta evaluación.
 */
public final class StrengthsAnalysis {

    /** Diferencia relativa con la referencia que se considera "en línea" (±10%). */
    static final double TOLERANCE = 0.10;
    private static final double EPSILON = 1e-9;
    /** Con menos partidos o intentos que esto, la evaluación es "muestra chica". */
    public static final int MIN_SAMPLE = 3;
    /** Diferencia de gol por partido en un tiempo que se considera equilibrada (±0,25). */
    static final double BALANCE_THRESHOLD = 0.25;
    static final String BALANCE_LABEL = "Parejo (±0,25)";
    /** % de los goles del máximo goleador: desde acá hay dependencia; hasta LOW los goles están repartidos. */
    static final double SCORER_DEPENDENCE = 35;
    static final double SCORER_SPREAD = 25;
    /** Goles mínimos del equipo para evaluar la dependencia goleadora. */
    static final int MIN_GOALS_FOR_DEPENDENCE = 10;

    private static final Locale ES = Locale.forLanguageTag("es-AR");

    private StrengthsAnalysis() {
    }

    static List<Indicator> evaluate(TeamReport t, LeagueContext league) {
        List<Indicator> out = new ArrayList<>();
        TeamRecord r = t.total();
        out.add(relative("Ataque", "Goles a favor por partido", r.goalsForPerGame(), "Liga", league.goalsPerTeamGame(),
                Unit.PER_GAME, true, r.played(), "partidos"));
        out.add(relative("Defensa", "Goles en contra por partido", r.goalsAgainstPerGame(), "Liga",
                league.goalsPerTeamGame(), Unit.PER_GAME, false, r.played(), "partidos"));
        out.add(relative("Local", "Efectividad de local", t.home().effectiveness(), "Liga", league.effectiveness(),
                Unit.PERCENT, true, t.home().played(), "partidos"));
        out.add(relative("Visitante", "Efectividad de visitante", t.away().effectiveness(), "Liga",
                league.effectiveness(), Unit.PERCENT, true, t.away().played(), "partidos"));
        if (!t.form().isEmpty()) {
            int pts = t.form().stream().mapToInt(o -> "G".equals(o) ? 3 : "E".equals(o) ? 1 : 0).sum();
            double formPct = 100.0 * pts / (t.form().size() * 3);
            out.add(relative("Forma", "Efectividad en los últimos " + t.form().size(), formPct, "Período",
                    r.effectiveness(), Unit.PERCENT, true, t.form().size(), "partidos"));
        }
        TimeProfile tp = t.timeProfile();
        if (tp.hasData()) {
            for (TimeBucket b : tp.byPeriod()) {
                double diff = (double) (b.goalsFor() - b.goalsAgainst()) / tp.matchesWithSheet();
                out.add(balance(b.period() == 1 ? "1er tiempo" : "2do tiempo", "Diferencia de gol por partido",
                        diff, tp.matchesWithSheet()));
            }
        }
        // Sin libres ni penales en la liga, la fuente no los distingue: no se evalúa (sería 0% contra 0%).
        if (league.setPieces()) {
            out.add(relative("Pelota parada", "Faltas directas convertidas", t.setPieces().directFreePct(), "Liga",
                    league.directFreePct(), Unit.PERCENT, true, t.setPieces().directFreeTaken(), "intentos"));
            out.add(relative("Pelota parada", "Penales convertidos", t.setPieces().penaltyPct(), "Liga",
                    league.penaltyPct(), Unit.PERCENT, true, t.setPieces().penaltiesTaken(), "intentos"));
        }
        out.add(relative("Disciplina", "Azules y rojas por partido", t.discipline().cardsPerGame(), "Liga",
                league.cardsPerTeamGame(), Unit.PER_GAME, false, t.discipline().matches(), "partidos"));
        if (t.fouls().matches() > 0 && league.matchesWithSheet() > 0) {
            out.add(relative("Disciplina", "Faltas por partido", t.fouls().ownPerGame(), "Liga",
                    t.fouls().leaguePerTeamGame(), Unit.PER_GAME, false, t.fouls().matches(), "fichas"));
        }
        if (tp.hasData()) {
            out.add(relative("Inferioridad", "Minutos con uno menos por partido", tp.shortHandedMinutesPerGame(),
                    "Con uno más", tp.powerPlayMinutesPerGame(), Unit.MINUTES, false, tp.matchesWithSheet(), "fichas"));
        }
        t.players().stream().max(Comparator.comparingDouble(PlayerLine::goalShare))
                .ifPresent(top -> out.add(scorerDependence(top, r.goalsFor())));
        return out;
    }

    /** Compara con una referencia: ±10% es "en línea"; mejor o peor según {@code higherIsBetter}. */
    public static Indicator relative(String area, String metric, double value, String refLabel, double reference,
                              Unit unit, boolean higherIsBetter, int sample, String sampleNoun) {
        if (sample < MIN_SAMPLE) {
            return new Indicator(area, metric, value, refLabel, reference, unit, Verdict.SMALL_SAMPLE,
                    "Pocos casos para evaluar (" + sample + " " + sampleNoun + ").");
        }
        String comparison = format(value, unit) + " contra " + format(reference, unit) + " ("
                + refLabel.toLowerCase(ES) + ")";
        if (reference == 0) {
            Verdict v = value == 0 ? Verdict.ON_PAR
                    : (value > 0) == higherIsBetter ? Verdict.STRENGTH : Verdict.WEAKNESS;
            return new Indicator(area, metric, value, refLabel, reference, unit, v, comparison + ".");
        }
        double diff = (value - reference) / Math.abs(reference);
        double better = higherIsBetter ? diff : -diff;
        // EPSILON: que un "exactamente 10%" no quede afuera por redondeo de punto flotante.
        Verdict v = better >= TOLERANCE - EPSILON ? Verdict.STRENGTH
                : better <= -TOLERANCE + EPSILON ? Verdict.WEAKNESS : Verdict.ON_PAR;
        String pct = Math.round(Math.abs(diff) * 100) == 0 ? "igual a la referencia"
                : String.format(ES, "%.0f%% %s", Math.abs(diff) * 100, diff >= 0 ? "más" : "menos");
        return new Indicator(area, metric, value, refLabel, reference, unit, v, comparison + ": " + pct + ".");
    }

    /** Diferencia de gol por partido en un tiempo: equilibrada entre -0,25 y +0,25. */
    static Indicator balance(String area, String metric, double diff, int sample) {
        if (sample < MIN_SAMPLE) {
            return new Indicator(area, metric, diff, BALANCE_LABEL, 0, Unit.DIFF, Verdict.SMALL_SAMPLE,
                    "Pocos casos para evaluar (" + sample + " fichas).");
        }
        Verdict v = diff > BALANCE_THRESHOLD ? Verdict.STRENGTH
                : diff < -BALANCE_THRESHOLD ? Verdict.WEAKNESS : Verdict.ON_PAR;
        String text = v == Verdict.ON_PAR ? "Tiempo parejo: " + format(diff, Unit.DIFF) + " goles por partido."
                : format(diff, Unit.DIFF) + " goles por partido de diferencia en el " + area.toLowerCase(ES) + ".";
        return new Indicator(area, metric, diff, BALANCE_LABEL, 0, Unit.DIFF, v, text);
    }

    static Indicator scorerDependence(PlayerLine top, int teamGoals) {
        String metric = "% de los goles del máximo goleador";
        if (teamGoals < MIN_GOALS_FOR_DEPENDENCE) {
            return new Indicator("Goleo", metric, top.goalShare(), "Umbral", SCORER_DEPENDENCE, Unit.PERCENT,
                    Verdict.SMALL_SAMPLE, "Pocos goles para evaluar (" + teamGoals + ").");
        }
        Verdict v = top.goalShare() >= SCORER_DEPENDENCE ? Verdict.WEAKNESS
                : top.goalShare() <= SCORER_SPREAD ? Verdict.STRENGTH : Verdict.ON_PAR;
        String text = top.name() + " hizo el " + format(top.goalShare(), Unit.PERCENT) + " de los goles"
                + switch (v) {
                    case WEAKNESS -> ": alta dependencia de un jugador (umbral " + format(SCORER_DEPENDENCE, Unit.PERCENT) + ").";
                    case STRENGTH -> ": goles repartidos (hasta " + format(SCORER_SPREAD, Unit.PERCENT) + ").";
                    default -> " (entre " + format(SCORER_SPREAD, Unit.PERCENT) + " y "
                            + format(SCORER_DEPENDENCE, Unit.PERCENT) + ").";
                };
        return new Indicator("Goleo", metric, top.goalShare(), "Umbral", SCORER_DEPENDENCE, Unit.PERCENT, v, text);
    }

    /** Formato de un valor según su unidad, igual que en el PDF. */
    public static String format(double v, Unit unit) {
        return switch (unit) {
            case PER_GAME -> String.format(ES, "%.2f", v);
            case PERCENT -> String.format(ES, "%.0f%%", v);
            case DIFF -> String.format(ES, "%+.2f", v);
            case MINUTES -> {
                long s = Math.round(v * 60);
                yield String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
            }
        };
    }
}
