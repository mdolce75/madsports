package com.madsports.report;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Lo común a los gráficos del PDF: la escala, la paleta (validada para daltonismo) y las piezas de dibujo (barras,
 * leyendas, ejes, texto) y el render a PNG como data URI.
 */
final class ChartCanvas {

    private ChartCanvas() {
    }

    /** Se dibuja a 3x para que se vea nítido impreso. */
    static final int SCALE = 3;
    static final Locale ES = Locale.forLanguageTag("es-AR");

    // Paleta (validada con scripts/validate_palette.js de la guía de visualización)
    static final Color WIN = new Color(0x2f855a);
    static final Color DRAW = new Color(0xd69e2e);
    static final Color LOSS = new Color(0xc53030);
    static final Color[] CATEGORICAL = {new Color(0x2a78d6), new Color(0xeb6834), new Color(0x1baf7a),
            new Color(0xeda100), new Color(0xe87ba4)};
    static final Color OTHER = new Color(0xa0aec0);
    // Colores de las tarjetas (los mismos de las tablas del informe)
    static final Color CARD_YELLOW = new Color(0xecc94b);
    static final Color CARD_BLUE = new Color(0x3182ce);
    static final Color CARD_RED = new Color(0xe53e3e);
    static final Color OWN = new Color(0x22b8f0);
    static final Color RIVAL_HALF = new Color(0x13203a);
    static final Color INK = new Color(0x1d2433);
    static final Color MUTED = new Color(0x52514e);
    static final Color GRID = new Color(0xe2e8f0);
    static final Color SURFACE = Color.WHITE;


    /** Barra con el extremo de datos redondeado (4px) y el lado de la base recto. */
    static void bar(Graphics2D g, double x, double y, double w, double h, Color c, boolean roundTop) {
        if (h <= 0) {
            return;
        }
        double r = Math.min(4, Math.min(w / 2, h));
        Path2D p = new Path2D.Double();
        if (roundTop) {
            p.moveTo(x, y + h);
            p.lineTo(x, y + r);
            p.quadTo(x, y, x + r, y);
            p.lineTo(x + w - r, y);
            p.quadTo(x + w, y, x + w, y + r);
            p.lineTo(x + w, y + h);
        } else {
            p.moveTo(x, y);
            p.lineTo(x, y + h - r);
            p.quadTo(x, y + h, x + r, y + h);
            p.lineTo(x + w - r, y + h);
            p.quadTo(x + w, y + h, x + w, y + h - r);
            p.lineTo(x + w, y);
        }
        p.closePath();
        g.setColor(c);
        g.fill(p);
    }

    static void legend(Graphics2D g, double x, double y, String[] names, Color[] colors) {
        g.setFont(font(Font.PLAIN, 9));
        FontMetrics fm = g.getFontMetrics();
        double cx = x;
        for (int i = 0; i < names.length; i++) {
            g.setColor(colors[i]);
            g.fill(new java.awt.geom.RoundRectangle2D.Double(cx, y - 8, 9, 9, 3, 3));
            g.setColor(INK);
            g.drawString(names[i], (float) (cx + 13), (float) y);
            cx += 13 + fm.stringWidth(names[i]) + 14;
        }
    }

    /** Grilla horizontal tenue con hasta 4 marcas enteras arriba de la base y, si {@code both}, también abajo. */
    static void yTicks(Graphics2D g, double left, double right, double zero, double unit, int max,
                               boolean both) {
        int step = Math.max(1, (int) Math.ceil(max / 4.0));
        g.setFont(font(Font.PLAIN, 8));
        FontMetrics fm = g.getFontMetrics();
        for (int v = step; v <= max; v += step) {
            for (int sign : both ? new int[]{1, -1} : new int[]{1}) {
                double y = zero - sign * v * unit;
                g.setColor(GRID);
                g.setStroke(new BasicStroke(1f));
                g.draw(new java.awt.geom.Line2D.Double(left, y, right, y));
                g.setColor(MUTED);
                String s = sign < 0 ? "−" + v : String.valueOf(v);
                g.drawString(s, (float) (left - 6 - fm.stringWidth(s)), (float) (y + 3));
            }
        }
    }

    static void baseline(Graphics2D g, double left, double right, double y) {
        g.setColor(new Color(0x94a3b8));
        g.setStroke(new BasicStroke(1f));
        g.draw(new java.awt.geom.Line2D.Double(left, y, right, y));
    }

    /** Etiquetas del eje x: solo los minutos ("0-5'"), con 1T / 2T una vez por tiempo. */
    static void xLabels(Graphics2D g, List<String> labels, double left, double slot, double y) {
        g.setFont(font(Font.PLAIN, 8));
        g.setColor(MUTED);
        for (int i = 0; i < labels.size(); i++) {
            String l = labels.get(i);
            String shortL = l.contains(" ") ? l.substring(l.indexOf(' ') + 1) : l;
            if (i == 0 || i == labels.size() / 2) {
                shortL = l;
            }
            centered(g, shortL, left + i * slot + slot / 2, y);
        }
    }

    /** Línea punteada entre el 1er y el 2do tiempo. */
    static void periodSeparator(Graphics2D g, int n, double left, double slot, double top, double bottom) {
        if (n % 2 != 0) {
            return;
        }
        double x = left + (n / 2.0) * slot;
        g.setColor(new Color(0x94a3b8));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
        g.draw(new java.awt.geom.Line2D.Double(x, top - 6, x, bottom));
        g.setStroke(new BasicStroke(1f));
    }

    static void centered(Graphics2D g, String s, double cx, double baselineY) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) baselineY);
    }

    /** Los gráficos se muestran achicados en media columna: la letra se agranda un 30% para que se lea impresa. */
    static final float FONT_SCALE = 1.3f;

    static Font font(int style, float size) {
        return new Font(Font.SANS_SERIF, style, 1).deriveFont(style, size * FONT_SCALE);
    }

    static int max(int[] v) {
        int m = 0;
        for (int x : v) {
            m = Math.max(m, x);
        }
        return m;
    }

    static String clock(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** Nombre corto para la leyenda: primer nombre y último apellido ("Simona Emma Demata Bianchi" -> "Simona Bianchi"). */
    static String shortName(String full) {
        String[] parts = full.trim().split("\\s+");
        if (parts.length <= 2) {
            return full;
        }
        String s = parts[0] + " " + parts[parts.length - 1];
        return s.length() > 18 ? s.substring(0, 17) + "…" : s;
    }

    /** Dibuja a 3x sobre fondo blanco y devuelve un data URI PNG. */
    static String render(int w, int h, Consumer<Graphics2D> draw) {
        BufferedImage img = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(SURFACE);
            g.fill(new Rectangle2D.Double(0, 0, w * SCALE, h * SCALE));
            g.scale(SCALE, SCALE);
            draw.accept(g);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
