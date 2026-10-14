package com.fppstats.analysis;

import com.fppstats.stats.ReportData;
import com.fppstats.stats.ReportData.LeagueContext;
import com.fppstats.stats.ReportData.MatchLine;
import com.fppstats.stats.ReportData.PlayerLine;
import com.fppstats.stats.ReportData.TeamRecord;
import com.fppstats.stats.ReportData.TeamReport;
import com.fppstats.stats.ReportData.TimeBucket;
import com.fppstats.stats.ReportData.TimeProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Análisis determinístico. Se usa sin API key de Claude o si el agente falla. */
@Component
public class RuleBasedAnalyst {

    /** Con menos intentos de falta directa el porcentaje no dice nada. */
    private static final int MIN_ATTEMPTS = 3;

    public AnalysisResult analyze(ReportData data) {
        List<String> summary = new ArrayList<>();
        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();
        List<AnalysisResult.KeyPlayer> keyPlayers = new ArrayList<>();
        String preview = "";
        List<String> trend = new ArrayList<>();
        LeagueContext league = data.league();

        for (TeamReport t : data.teams()) {
            TeamRecord r = t.total();
            String prefix = data.teams().size() > 1 ? t.code() + ": " : "";
            if (r.played() == 0) {
                summary.add(prefix + "todavía no disputó partidos en " + data.torneo() + ".");
                continue;
            }
            summary.add(String.format(Locale.ROOT, "%s%d PJ, %d ganados, %d empatados y %d perdidos; %d goles a favor y %d en contra (%.0f%% de efectividad).",
                    prefix, r.played(), r.won(), r.drawn(), r.lost(), r.goalsFor(), r.goalsAgainst(), r.effectiveness()));

            double gf = r.goalsForPerGame();
            double ga = r.goalsAgainstPerGame();
            double avg = league.goalsPerTeamGame();
            if (avg > 0) {
                (gf >= avg ? strengths : weaknesses).add(String.format(Locale.ROOT,
                        "%sGoles a favor por partido: %.2f (promedio de la liga %.2f).", prefix, gf, avg));
                (ga <= avg ? strengths : weaknesses).add(String.format(Locale.ROOT,
                        "%sGoles en contra por partido: %.2f (promedio de la liga %.2f).", prefix, ga, avg));
            }
            if (t.setPieces().directFreeTaken() >= MIN_ATTEMPTS) {
                (t.setPieces().directFreePct() >= league.directFreePct() ? strengths : weaknesses).add(String.format(Locale.ROOT,
                        "%sFaltas directas: %d/%d (%.0f%%; liga %.0f%%).", prefix, t.setPieces().directFreeScored(),
                        t.setPieces().directFreeTaken(), t.setPieces().directFreePct(), league.directFreePct()));
            }
            if (league.cardsPerTeamGame() > 0 && t.discipline().cardsPerGame() > league.cardsPerTeamGame() * 1.25) {
                weaknesses.add(String.format(Locale.ROOT, "%sDisciplina: %.2f tarjetas por partido (liga %.2f).",
                        prefix, t.discipline().cardsPerGame(), league.cardsPerTeamGame()));
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
                trend.add(String.format(Locale.ROOT, "%sPor partido juega %.1f min con uno menos y %.1f min con uno más.",
                        prefix, tp.shortHandedMinutesPerGame(), tp.powerPlayMinutesPerGame()));
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
                p.goals(), p.games(), p.goalShare(), p.assists() > 0 ? ", " + p.assists() + " asistencias" : "");
    }

    private static String previousMeeting(TeamReport t, String rival) {
        return t.rivals().stream().filter(r -> r.rival().equalsIgnoreCase(rival)).findFirst()
                .map(r -> String.format(Locale.ROOT, "; antecedente en el período: %dG %dE %dP (%d-%d).",
                        r.won(), r.drawn(), r.lost(), r.goalsFor(), r.goalsAgainst()))
                .orElse(".");
    }
}
