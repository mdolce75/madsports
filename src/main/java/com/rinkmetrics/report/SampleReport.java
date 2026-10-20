package com.rinkmetrics.report;

import com.rinkmetrics.analysis.AnalysisResult;
import com.rinkmetrics.analysis.RuleBasedAnalyst;
import com.rinkmetrics.player.SamplePlayerData;
import com.rinkmetrics.sidgad.model.Competition.Category;
import com.rinkmetrics.sidgad.model.Federacion;
import com.rinkmetrics.stats.ReportData;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Informe modelo con datos ficticios, para mostrar el formato sin consultar SIDGAD ni la API de Gemini. */
@Component
public class SampleReport {

    private final PdfRenderer renderer;
    private final RuleBasedAnalyst rules;
    private final Clock clock;
    private volatile LocalDate builtOn;
    private volatile byte[] pdf;
    private volatile LocalDate playerBuiltOn;
    private volatile byte[] playerPdf;

    public SampleReport(PdfRenderer renderer, RuleBasedAnalyst rules, Clock clock) {
        this.renderer = renderer;
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * Informe modelo del club completo: rama Masculino (Primera y Juvenil) y Femenino (Infantil). Se genera una vez por
     * día (las fechas del ejemplo son relativas a hoy).
     */
    public byte[] pdf() {
        LocalDate today = LocalDate.now(clock);
        if (pdf == null || !today.equals(builtOn)) {
            synchronized (this) {
                if (pdf == null || !today.equals(builtOn)) {
                    pdf = renderer.clubPdf(clubReport(today), true);
                    builtOn = today;
                }
            }
        }
        return pdf;
    }

    ClubReport clubReport(LocalDate today) {
        String logo = Crests.sample("CE");
        ClubReport.CategoryBlock primera = block("Primera", SampleData.build(today));
        ClubReport.CategoryBlock juvenil = block("Juvenil",
                SampleData.build(today, new Category("JUVENIL", "MASCULINO"), 2027, 9011));
        ClubReport.CategoryBlock infantil = block("Infantil",
                SampleData.build(today, new Category("INFANTIL", "FEMENINO"), 2028, 9021));
        ReportData.ClubInfo club = primera.data().club();
        return new ClubReport(new ReportData.ClubInfo(club.id(), club.code(), club.name(), logo),
                "Temporada " + today.getYear() + " · Apertura + Clausura", today, Federacion.FPP,
                List.of(new ClubReport.Branch("Masculino", List.of(primera, juvenil)),
                        new ClubReport.Branch("Femenino", List.of(infantil))),
                List.of(), List.of());
    }

    private ClubReport.CategoryBlock block(String name, ReportData data) {
        return new ClubReport.CategoryBlock(name, data, data.teams().getFirst(), analysis(data));
    }

    /** Informe modelo de jugador ("Tomás Ejemplo", de Juniors, que también juega en Primera); uno por día. */
    public byte[] playerPdf() {
        LocalDate today = LocalDate.now(clock);
        if (playerPdf == null || !today.equals(playerBuiltOn)) {
            synchronized (this) {
                if (playerPdf == null || !today.equals(playerBuiltOn)) {
                    playerPdf = renderer.playerPdf(SamplePlayerData.build(today, Crests.sample("CE")), true);
                    playerBuiltOn = today;
                }
            }
        }
        return playerPdf;
    }

    /** El texto de análisis sale del analista por reglas (no consume la API), con la fuente aclarada. */
    AnalysisResult analysis(ReportData data) {
        AnalysisResult a = rules.analyze(data);
        return new AnalysisResult("texto de ejemplo — en los informes reales lo redacta la IA (Gemini)",
                a.summary(), a.strengths(), a.weaknesses(), a.keyPlayers(), a.nextOpponentPreview(), a.trend(),
                a.foulsAnalysis());
    }
}
