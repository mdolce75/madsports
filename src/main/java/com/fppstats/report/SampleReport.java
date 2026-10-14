package com.fppstats.report;

import com.fppstats.analysis.AnalysisResult;
import com.fppstats.analysis.RuleBasedAnalyst;
import com.fppstats.stats.ReportData;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** Informe modelo con datos ficticios, para mostrar el formato sin consultar SIDGAD ni la API de Gemini. */
@Component
public class SampleReport {

    private final PdfRenderer renderer;
    private final RuleBasedAnalyst rules;
    private final Clock clock;
    private volatile LocalDate builtOn;
    private volatile byte[] pdf;

    public SampleReport(PdfRenderer renderer, RuleBasedAnalyst rules, Clock clock) {
        this.renderer = renderer;
        this.rules = rules;
        this.clock = clock;
    }

    /** Se genera una vez por día (las fechas del ejemplo son relativas a hoy). */
    public byte[] pdf() {
        LocalDate today = LocalDate.now(clock);
        if (pdf == null || !today.equals(builtOn)) {
            synchronized (this) {
                if (pdf == null || !today.equals(builtOn)) {
                    ReportData data = SampleData.build(today).withLogo(Crests.sample("CE"));
                    pdf = renderer.pdf(data, analysis(data), true);
                    builtOn = today;
                }
            }
        }
        return pdf;
    }

    /** El texto de análisis sale del analista por reglas (no consume la API), con la fuente aclarada. */
    AnalysisResult analysis(ReportData data) {
        AnalysisResult a = rules.analyze(data);
        return new AnalysisResult("texto de ejemplo — en los informes reales lo redacta la IA (Gemini)",
                a.summary(), a.strengths(), a.weaknesses(), a.keyPlayers(), a.nextOpponentPreview(), a.trend(),
                a.foulsAnalysis());
    }
}
