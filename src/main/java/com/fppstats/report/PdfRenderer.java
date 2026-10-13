package com.fppstats.report;

import com.fppstats.analysis.AnalysisResult;
import com.fppstats.stats.ReportData;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Arma el PDF desde la plantilla {@code templates/report.html}. */
@Component
public class PdfRenderer {

    private final ITemplateEngine templates;

    public PdfRenderer(ITemplateEngine templates) {
        this.templates = templates;
    }

    public String html(ReportData data, AnalysisResult analysis) {
        return html(data, analysis, false);
    }

    /** @param sample informe modelo: cada página lleva la marca "datos ficticios" */
    public String html(ReportData data, AnalysisResult analysis, boolean sample) {
        Context ctx = new Context(Locale.forLanguageTag("es-AR"));
        ctx.setVariable("d", data);
        ctx.setVariable("a", analysis);
        ctx.setVariable("f", new Format());
        ctx.setVariable("sample", sample);
        return templates.process("report", ctx);
    }

    public byte[] pdf(ReportData data, AnalysisResult analysis) {
        return pdf(data, analysis, false);
    }

    public byte[] pdf(ReportData data, AnalysisResult analysis, boolean sample) {
        // openhtmltopdf exige XML bien formado: jsoup normaliza el HTML5 de Thymeleaf a un DOM W3C.
        org.w3c.dom.Document dom = new W3CDom().fromJsoup(Jsoup.parse(html(data, analysis, sample)));
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new PdfRendererBuilder()
                    .useFastMode()
                    .withW3cDocument(dom, "about:blank")
                    .toStream(out)
                    .run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF", e);
        }
    }

    /** Formateo usado desde la plantilla. */
    public static class Format {
        private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        public String dec(double v) {
            return String.format(Locale.forLanguageTag("es-AR"), "%.2f", v);
        }

        public String pct(double v) {
            return String.format(Locale.forLanguageTag("es-AR"), "%.0f%%", v);
        }

        public String date(LocalDate d) {
            return d == null ? "a confirmar" : d.format(DATE);
        }

        /** Ancho de barra (0-100) relativo al máximo, para gráficos simples en CSS. */
        public String bar(double value, double max) {
            double w = max <= 0 ? 0 : Math.max(0, Math.min(100, 100 * value / max));
            return String.format(Locale.ROOT, "width: %.1f%%", w);
        }

        public double max(double a, double b) {
            return Math.max(a, b);
        }

        public String fraction(int scored, int taken) {
            return taken == 0 ? "-" : scored + "/" + taken;
        }
    }
}
