package com.rinkmetrics.analysis;

import com.rinkmetrics.stats.ReportData;
import com.rinkmetrics.stats.ReportData.FoulSituation;
import com.rinkmetrics.stats.ReportData.Fouls;
import com.rinkmetrics.stats.ReportData.Indicator;
import com.rinkmetrics.stats.ReportData.MatchLine;
import com.rinkmetrics.stats.ReportData.PlayerLine;
import com.rinkmetrics.stats.ReportData.TeamRecord;
import com.rinkmetrics.stats.ReportData.TeamReport;
import com.rinkmetrics.stats.ReportData.TimeBucket;
import com.rinkmetrics.stats.ReportData.TimeProfile;
import com.rinkmetrics.stats.ReportData.Verdict;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Análisis determinístico. Se usa sin API key de Gemini o si Gemini falla. */
@Component
public class RuleBasedAnalyst {

    private static final Locale ES = Locale.forLanguageTag("es-AR");

    public AnalysisResult analyze(ReportData data) {
        List<String> summary = new ArrayList<>();
        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();
        List<AnalysisResult.KeyPlayer> keyPlayers = new ArrayList<>();
        String preview = "";
        List<String> trend = new ArrayList<>();

        for (TeamReport t : data.teams()) {
            TeamRecord r = t.total();
            String prefix = data.teams().size() > 1 ? t.code() + ": " : "";
            if (r.played() == 0) {
                summary.add(prefix + "todavía no disputó partidos en " + data.torneo() + ".");
                continue;
            }
            summary.add(String.format(Locale.ROOT, "%s%d PJ, %d ganados, %d empatados y %d perdidos; %d goles a favor y %d en contra (%.0f%% de efectividad).",
                    prefix, r.played(), r.won(), r.drawn(), r.lost(), r.goalsFor(), r.goalsAgainst(), r.effectiveness()));

            // Fortalezas y debilidades: las de la evaluación por indicadores (StrengthsAnalysis).
            for (Indicator i : t.indicators()) {
                String line = prefix + i.area() + " — " + i.metric() + ": " + i.explanation();
                if (i.verdict() == Verdict.FORTALEZA) {
                    strengths.add(line);
                } else if (i.verdict() == Verdict.DEBILIDAD) {
                    weaknesses.add(line);
                }
            }
            t.players().stream().filter(p -> p.goals() > 0).limit(2).forEach(p -> keyPlayers.add(
                    new AnalysisResult.KeyPlayer(p.name(), describe(p))));
            TimeProfile tp = t.timeProfile();
            if (tp.hasData()) {
                tp.buckets().stream().max(Comparator.comparingInt(TimeBucket::goalsFor)).filter(b -> b.goalsFor() > 0)
                        .ifPresent(b -> trend.add(String.format(Locale.ROOT, "%sTramo con más goles a favor: %s (%d).",
                                prefix, b.label(), b.goalsFor())));
                tp.buckets().stream().max(Comparator.comparingInt(TimeBucket::goalsAgainst)).filter(b -> b.goalsAgainst() > 0)
                        .ifPresent(b -> trend.add(String.format(Locale.ROOT, "%sTramo con más goles en contra: %s (%d).",
                                prefix, b.label(), b.goalsAgainst())));
                trend.add(prefix + "Por partido juega " + clock(tp.shortHandedMinutesPerGame()) + " con uno menos y "
                        + clock(tp.powerPlayMinutesPerGame()) + " con uno más.");
            }
            if (!t.form().isEmpty()) {
                trend.add(prefix + "Últimos resultados: " + String.join(" ", t.form()) + ".");
            }
            if (preview.isEmpty() && !t.upcoming().isEmpty()) {
                MatchLine next = t.upcoming().getFirst();
                preview = prefix + "Próximo partido: " + (next.home() ? "local" : "visitante") + " ante " + next.rival()
                        + previousMeeting(t, next.rival());
            }
        }
        return new AnalysisResult("Reglas", String.join(" ", summary), strengths, weaknesses, keyPlayers,
                preview.isEmpty() ? "No hay partidos programados." : preview, String.join(" ", trend),
                foulsAnalysis(data));
    }

    /** Minutos mínimos en una situación (ganando, empatando, perdiendo) para comparar su ritmo de faltas. */
    private static final int MIN_SECONDS_IN_SITUATION = 5 * 60;

    /**
     * Análisis escrito de las faltas, con cifras calculadas: volumen contra la liga y los rivales, distribución por
     * tiempo y tramo, ritmo según el marcador y relación con las tarjetas azules.
     */
    public List<String> foulsAnalysis(ReportData data) {
        List<String> out = new ArrayList<>();
        for (TeamReport t : data.teams()) {
            String prefix = data.teams().size() > 1 ? t.code() + ": " : "";
            Fouls f = t.fouls();
            if (f.matches() == 0) {
                out.add(prefix + "No hay fichas de partido publicadas para analizar las faltas.");
                continue;
            }
            // Volumen
            StringBuilder volume = new StringBuilder(prefix).append(String.format(ES,
                    "Comete %.2f faltas por partido (%d en %d partidos con ficha)", f.ownPerGame(), f.own(), f.matches()));
            if (data.league().matchesWithSheet() > 0 && f.leaguePerTeamGame() > 0) {
                double diff = (f.ownPerGame() - f.leaguePerTeamGame()) / f.leaguePerTeamGame() * 100;
                volume.append(String.format(ES, ", %s el promedio de la liga (%.2f)",
                        Math.abs(diff) < 10 ? "en línea con" : String.format(ES, "un %.0f%% %s que", Math.abs(diff),
                                diff > 0 ? "más" : "menos"), f.leaguePerTeamGame()));
            }
            volume.append(String.format(ES, ". Sus rivales le cometen %.2f por partido en esos mismos partidos", f.rivalPerGame()));
            double vsRival = f.rivalPerGame() == 0 ? 0 : (f.ownPerGame() - f.rivalPerGame()) / f.rivalPerGame() * 100;
            volume.append(Math.abs(vsRival) < 10 ? ", un volumen parecido al suyo."
                    : vsRival > 0 ? ": comete más faltas que sus rivales." : ": comete menos faltas que sus rivales.");
            out.add(volume.toString());

            // Distribución por tiempo y tramo
            TimeProfile tp = t.timeProfile();
            int first = tp.byPeriod().get(0).foulsOwn();
            int second = tp.byPeriod().get(1).foulsOwn();
            if (first + second > 0) {
                TimeBucket peak = tp.buckets().stream().max(Comparator.comparingInt(TimeBucket::foulsOwn)).orElseThrow();
                TimeBucket low = tp.buckets().stream().min(Comparator.comparingInt(TimeBucket::foulsOwn)).orElseThrow();
                out.add(prefix + String.format(ES, "Reparto por tiempo: %d en el 1er tiempo y %d en el 2do (%.0f%% en el 2do). "
                                + "El tramo con más faltas propias es %s (%d) y el de menos, %s (%d).",
                        first, second, 100.0 * second / (first + second), peak.label(), peak.foulsOwn(),
                        low.label(), low.foulsOwn()));
            }

            // Según el marcador
            FoulSituation s = tp.foulSituation();
            List<String> rates = new ArrayList<>();
            String worst = null;
            double worstRate = -1;
            for (Object[] st : new Object[][]{{"ganando", s.whenAhead(), s.secondsAhead(), s.per10Ahead()},
                    {"empatando", s.whenLevel(), s.secondsLevel(), s.per10Level()},
                    {"perdiendo", s.whenBehind(), s.secondsBehind(), s.per10Behind()}}) {
                int seconds = (int) st[2];
                if (seconds >= MIN_SECONDS_IN_SITUATION) {
                    double rate = (double) st[3];
                    rates.add(String.format(ES, "%.2f cada 10 minutos %s (%d faltas en %s)", rate, st[0], (int) st[1],
                            clock(seconds / 60.0)));
                    if (rate > worstRate) {
                        worstRate = rate;
                        worst = (String) st[0];
                    }
                }
            }
            if (rates.size() >= 2) {
                out.add(prefix + "Según el marcador: " + String.join(", ", rates) + ". Comete faltas más seguido cuando va "
                        + worst + ".");
            } else if (rates.size() == 1) {
                out.add(prefix + "Según el marcador: " + rates.getFirst() + "; en las otras situaciones jugó menos de 5 minutos.");
            }

            // Relación con las tarjetas azules
            int blue = tp.byPeriod().stream().mapToInt(TimeBucket::blueOwn).sum() + tp.unrecorded().blueOwn();
            int sheetFouls = first + second + tp.unrecorded().foulsOwn();
            if (blue > 0 && sheetFouls > 0) {
                out.add(prefix + String.format(ES, "Recibió %d tarjetas azules en esos partidos: una cada %.1f faltas.",
                        blue, (double) sheetFouls / blue));
            } else if (sheetFouls > 0) {
                out.add(prefix + "No recibió tarjetas azules en esos partidos.");
            }
        }
        return out;
    }

    private static String describe(PlayerLine p) {
        return String.format(Locale.ROOT, "%d goles en %d partidos (%.0f%% de los goles del equipo).",
                p.goals(), p.games(), p.goalShare());
    }

    private static String previousMeeting(TeamReport t, String rival) {
        return t.rivals().stream().filter(r -> r.rival().equalsIgnoreCase(rival)).findFirst()
                .map(r -> String.format(Locale.ROOT, "; antecedente en el período: %dG %dE %dP (%d-%d).",
                        r.won(), r.drawn(), r.lost(), r.goalsFor(), r.goalsAgainst()))
                .orElse(".");
    }

    /** Minutos con decimales como reloj (1.333 -> "1:20"), igual que en el PDF. */
    private static String clock(double minutes) {
        long s = Math.round(minutes * 60);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
