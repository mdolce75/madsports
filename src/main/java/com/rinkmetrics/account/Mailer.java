package com.rinkmetrics.account;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;

/**
 * Envía los mails de la cuenta por SMTP (Resend, Brevo, Gmail...): HTML con la identidad de la web y el logo como
 * imagen embebida, más la versión en texto. Sin SMTP configurado ({@code SMTP_HOST} vacío, como en los tests) el
 * mail se escribe en el log, con su enlace, y no se envía.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final ITemplateEngine templates;
    private final String host;
    private final String from;
    private final byte[] logo = logo();

    public Mailer(ObjectProvider<JavaMailSender> sender, ITemplateEngine templates,
                  @Value("${spring.mail.host:}") String host, @Value("${rinkmetrics.mail.from}") String from) {
        this.sender = sender;
        this.templates = templates;
        this.host = host;
        this.from = from;
    }

    /** HTML del mail (con la imagen del logo como {@code cid:logo}). */
    public String html(MailContent m) {
        Context ctx = new Context(Locale.forLanguageTag("es-AR"));
        ctx.setVariable("m", m);
        return templates.process("mail/cuenta", ctx);
    }

    /** @return false si no se pudo enviar (el error queda en el log; el usuario puede pedir otro mail) */
    public boolean send(MailContent m) {
        JavaMailSender smtp = host == null || host.isBlank() ? null : sender.getIfAvailable();
        if (smtp == null) {
            log.info("Mail sin SMTP configurado (no se envía) para {}: {}\n{}", m.to(), m.subject(), m.text());
            return true;
        }
        try {
            MimeMessage msg = smtp.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(m.to());
            helper.setSubject(m.subject());
            helper.setText(m.text(), html(m));
            helper.addInline("logo", new ByteArrayResource(logo), "image/png");
            smtp.send(msg);
            log.info("Mail enviado a {}: {}", m.to(), m.subject());
            return true;
        } catch (MessagingException | MailException e) {
            log.error("No se pudo enviar el mail a {}: {}", m.to(), e.getMessage());
            return false;
        }
    }

    /** Logo de la web (pista con barras de métricas sobre celeste), en PNG: los clientes de mail no muestran SVG. */
    static byte[] logo() {
        int px = 132;   // 44 px a 3x
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double k = px / 64.0;   // mismas coordenadas que el SVG de la web (viewBox 64x64)
            g.scale(k, k);
            g.setColor(new Color(0x0d968b));
            g.fill(new RoundRectangle2D.Double(0, 0, 64, 64, 28, 28));
            // Pista (rectángulo de esquinas redondeadas) con tres barras de métricas, como el SVG de la web.
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(4));
            g.draw(new RoundRectangle2D.Double(8, 15, 48, 34, 28, 28));
            g.fill(new RoundRectangle2D.Double(20, 33, 6, 9, 3, 3));
            g.fill(new RoundRectangle2D.Double(29, 27, 6, 15, 3, 3));
            g.fill(new RoundRectangle2D.Double(38, 21, 6, 21, 3, 3));
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
