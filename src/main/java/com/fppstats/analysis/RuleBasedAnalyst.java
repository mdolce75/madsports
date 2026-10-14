package com.fppstats.analysis;

import com.fppstats.stats.ReportData;
import com.fppstats.stats.ReportData.Indicator;
import com.fppstats.stats.ReportData.MatchLine;
import com.fppstats.stats.ReportData.PlayerLine;
import com.fppstats.stats.ReportData.TeamRecord;
import com.fppstats.stats.ReportData.TeamReport;
import com.fppstats.stats.ReportData.TimeBucket;
import com.fppstats.stats.ReportData.TimeProfile;
import com.fppstats.stats.ReportData.Verdict;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Análisis determinístico. Se usa sin API key de Gemini o si Gemini falla. */
@Component
public class RuleBasedAnalyst {

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
                preview.isEmpty() ? "No hay partidos programados." : preview, String.join(" ", trend));
    }

    private static String describe(PlayerLine p) {
        return String.format(Locale.ROOT, "%d goles en %d partidos (%.0f%% de los goles del equipo)%s.",
                p.goals(), p.games(), p.goalShare(), p.assists() > 0 ? ", " + p.assists() + (p.assists() == 1 ? " asistencia" : " asistencias") : "");
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
