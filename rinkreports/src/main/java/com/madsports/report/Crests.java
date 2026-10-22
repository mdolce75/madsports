package com.madsports.report;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Escudos de los clubes para la cabecera del PDF. Los logos de SIDGAD son PNG con fondo transparente y de tamaños y
 * proporciones muy distintas (de 150x150 a 739x1200): se encajan centrados en una placa blanca cuadrada de esquinas
 * redondeadas, que se ve igual sobre la banda oscura de la cabecera que sobre el fondo blanco de las secciones.
 */
public final class Crests {

    /** Lado de la placa en puntos; se dibuja a 3x para que se vea nítido impreso. */
    private static final int SIZE = 64;
    private static final int SCALE = 3;
    private static final double PADDING = 0.12;

    private Crests() {
    }

    /** Placa con el logo del club como data URI PNG, o {@code null} si la imagen no se puede leer. */
    public static String tile(byte[] image) {
        if (image == null || image.length == 0) {
            return null;
        }
        BufferedImage logo;
        try {
            logo = ImageIO.read(new ByteArrayInputStream(image));
        } catch (IOException e) {
            return null;
        }
        if (logo == null || logo.getWidth() == 0 || logo.getHeight() == 0) {
            return null;
        }
        return render(g -> {
            double box = SIZE * (1 - 2 * PADDING);
            double k = Math.min(box / logo.getWidth(), box / logo.getHeight());
            double w = logo.getWidth() * k;
            double h = logo.getHeight() * k;
            g.drawImage(logo, (int) Math.round((SIZE - w) / 2 * SCALE), (int) Math.round((SIZE - h) / 2 * SCALE),
                    (int) Math.round(w * SCALE), (int) Math.round(h * SCALE), null);
        });
    }

    /** Escudo genérico con iniciales, para el informe de ejemplo (club ficticio). */
    public static String sample(String initials) {
        return render(g -> {
            g.scale(SCALE, SCALE);
            double d = SIZE * (1 - 2 * PADDING);
            double o = (SIZE - d) / 2;
            g.setColor(new Color(0x13203a));
            g.fill(new Ellipse2D.Double(o, o, d, d));
            g.setColor(new Color(0x22b8f0));
            g.fill(new Ellipse2D.Double(o + 4, o + 4, d - 8, d - 8));
            g.setColor(new Color(0x13203a));
            g.fill(new Ellipse2D.Double(o + 7, o + 7, d - 14, d - 14));
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(initials, (float) (SIZE - fm.stringWidth(initials)) / 2,
                    (float) (SIZE + fm.getAscent() - fm.getDescent()) / 2);
        });
    }

    private static String render(Consumer<Graphics2D> draw) {
        int px = SIZE * SCALE;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(0, 0, px, px, 14 * SCALE, 14 * SCALE));
            draw.accept(g);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            return null;
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
