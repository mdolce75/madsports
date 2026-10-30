package com.madsports.report;

import com.madsports.analysis.AnalysisResult;
import com.madsports.pdf.Templates;
import com.madsports.player.PlayerReport;
import com.madsports.sidgad.model.Federation;
import com.madsports.source.DataSource;
import com.madsports.stats.ReportData;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Los informes de RinkReports desde sus plantillas: {@code templates/report.html} (una categoría),
 * {@code club-report.html} (el club completo) y {@code player-report.html} (un jugador). Cada una lleva los gráficos
 * ({@code c}), la fuente de los datos de la federación ({@code src}: "SIDGAD") y, en los de ejemplo, la marca "datos
 * ficticios" ({@code sample}). El paso a HTML y a PDF es el de
 * {@link Templates}, común a los productos.
 */
@Component
public class PdfRenderer {

    private final Templates templates;

    public PdfRenderer(Templates templates) {
        this.templates = templates;
    }

    public String html(ReportData data, AnalysisResult analysis) {
        return html(data, analysis, false);
    }

    /** @param sample informe modelo: cada página lleva la marca "datos ficticios" */
    public String html(ReportData data, AnalysisResult analysis, boolean sample) {
        return render("report", sample, "d", data, "a", analysis, "src", sourceOf(data.federation()));
    }

    public byte[] pdf(ReportData data, AnalysisResult analysis) {
        return pdf(data, analysis, false);
    }

    public byte[] pdf(ReportData data, AnalysisResult analysis, boolean sample) {
        return Templates.toPdf(html(data, analysis, sample));
    }

    /** Informe del club completo (todas sus ramas y categorías), plantilla {@code templates/club-report.html}. */
    public String clubHtml(ClubReport report, boolean sample) {
        return render("club-report", sample, "r", report, "src", sourceOf(report.federation()));
    }

    public byte[] clubPdf(ClubReport report) {
        return clubPdf(report, false);
    }

    public byte[] clubPdf(ClubReport report, boolean sample) {
        return Templates.toPdf(clubHtml(report, sample));
    }

    /** Informe de un jugador (perfil PLAYERFPP), plantilla {@code templates/player-report.html}. */
    public String playerHtml(PlayerReport report) {
        return playerHtml(report, false);
    }

    /** @param sample informe modelo: cada página lleva la marca "datos ficticios" */
    public String playerHtml(PlayerReport report, boolean sample) {
        return render("player-report", sample, "r", report, "src", sourceOf(report.federation()));
    }

    public byte[] playerPdf(PlayerReport report) {
        return playerPdf(report, false);
    }

    public byte[] playerPdf(PlayerReport report, boolean sample) {
        return Templates.toPdf(playerHtml(report, sample));
    }

    /** La fuente de los datos de la federación, como se nombra en el informe (los tests prueban otra). */
    protected DataSource sourceOf(Federation f) {
        return f.source();
    }

    /** @param vars pares nombre, valor (un valor puede ser null: el informe sin análisis) */
    private String render(String template, boolean sample, Object... vars) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < vars.length; i += 2) {
            m.put((String) vars[i], vars[i + 1]);
        }
        m.put("c", new Charts());
        m.put("sample", sample);
        return templates.html(template, m);
    }
}
