package com.madsports.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;

/**
 * Plantillas Thymeleaf a HTML y a PDF, para cualquier producto: los informes de RinkReports y el de RinkMetrics. Cada
 * plantilla recibe sus variables y {@code f}, el formateo de siempre ({@link Format}).
 */
@Component
public class Plantillas {

    private final ITemplateEngine templates;

    public Plantillas(ITemplateEngine templates) {
        this.templates = templates;
    }

    /** @param template nombre de la plantilla en {@code templates/} ("dt-informe") */
    public String html(String template, Map<String, ?> vars) {
        Context ctx = new Context(Locale.forLanguageTag("es-AR"));
        vars.forEach(ctx::setVariable);
        ctx.setVariable("f", new Format());
        return templates.process(template, ctx);
    }

    public byte[] pdf(String template, Map<String, ?> vars) {
        return toPdf(html(template, vars));
    }

    /** El HTML ya armado, a PDF. */
    public static byte[] toPdf(String html) {
        // openhtmltopdf exige XML bien formado: jsoup normaliza el HTML5 de Thymeleaf a un DOM W3C.
        org.w3c.dom.Document dom = new W3CDom().fromJsoup(Jsoup.parse(html));
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
}
