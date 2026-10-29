package com.madsports.coach;

import com.madsports.coach.CoachReport.Share;
import com.madsports.coach.CoachReport.Slice;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tortas del PDF del DT (el reparto de cada métrica entre los jugadores), dibujadas en Java2D como PNG y embebidas
 * como data URI. Los colores van en orden fijo (validados para daltonismo entre porciones vecinas) y la leyenda lleva
 * el nombre, la cifra y el porcentaje de cada porción: nunca depende solo del color. La pantalla dibuja lo mismo en
 * SVG (report.js).
 */
final class CoachCharts {

    /** Una torta lista para la plantilla. */
    public record Pie(String title, String image) {
    }

    private static final int SCALE = 3;
    private static final Locale ES = Locale.forLanguageTag("es-AR");
    private static final Color[] CATEGORICAL = {new Color(0x2a78d6), new Color(0xeb6834), new Color(0x1baf7a),
            new Color(0xeda100), new Color(0x8b5cf6), new Color(0xbe185d)};
    private static final Color OTHER = new Color(0xa0aec0);
    private static final Color INK = new Color(0x1d2433);
    private static final Color MUTED = new Color(0x52514e);
    private static final Color SURFACE = Color.WHITE;

    private CoachCharts() {
    }

    /** Las tortas de las métricas que alguien tuvo (las vacías se nombran aparte), de a tres por fila. */
    static List<List<Pie>> pies(CoachReport r) {
        List<Pie> all = r.shares().stream().filter(s -> s.total() > 0).map(s -> new Pie(s.title(), donut(s))).toList();
        List<List<Pie>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i += 3) {
            rows.add(all.subList(i, Math.min(i + 3, all.size())));
        }
        return rows;
    }

    static String donut(Share share) {
        List<Slice> slices = share.slices();
        double total = share.total();
        // La dona arriba y las referencias abajo, una por porción.
        int w = 200;
        double size = 92;
        int h = (int) (size + 16 + slices.size() * 17);
        return render(w, h, g -> {
            double x = (w - size) / 2;
            double y = 4;
            double thickness = 21;
            double start = 90;
            for (int i = 0; i < slices.size(); i++) {
                double extent = -360 * slices.get(i).value() / total;
                g.setColor(color(slices, i));
                g.fill(new Arc2D.Double(x, y, size, size, start, extent, Arc2D.PIE));
                start += extent;
            }
            // Separadores de 2px entre porciones y el agujero del centro
            g.setColor(SURFACE);
            g.setStroke(new BasicStroke(2f));
            if (slices.size() > 1) {
                start = 90;
                for (Slice s : slices) {
                    double a = Math.toRadians(start);
                    g.draw(new Line2D.Double(x + size / 2, y + size / 2,
                            x + size / 2 + Math.cos(a) * size / 2, y + size / 2 - Math.sin(a) * size / 2));
                    start += -360 * s.value() / total;
                }
            }
            g.fill(new Ellipse2D.Double(x + thickness, y + thickness, size - 2 * thickness, size - 2 * thickness));
            g.setColor(INK);
            g.setFont(font(Font.BOLD, 16));
            centered(g, String.valueOf(share.total()), x + size / 2, y + size / 2 + 3);
            g.setColor(MUTED);
            g.setFont(font(Font.PLAIN, 8));
            centered(g, share.unit(), x + size / 2, y + size / 2 + 15);
            // Leyenda: nombre, cifra y porcentaje
            double lx = 4;
            double ly = y + size + 22;
            for (int i = 0; i < slices.size(); i++) {
                Slice s = slices.get(i);
                g.setColor(color(slices, i));
                g.fill(new RoundRectangle2D.Double(lx, ly - 9, 10, 10, 3, 3));
                String v = s.value() + "  " + String.format(ES, "%.0f%%", 100 * s.value() / total);
                g.setFont(font(Font.BOLD, 9f));
                double vx = w - 4 - g.getFontMetrics().stringWidth(v);
                g.setColor(INK);
                g.drawString(v, (float) vx, (float) ly);
                g.setFont(font(Font.PLAIN, 9f));
                g.drawString(fit(g, s.name(), vx - lx - 22), (float) (lx + 15), (float) ly);
                ly += 17;
            }
        });
    }

    /** El color de la porción i: en orden fijo; "Otros" (la última, sin jugador), gris. */
    private static Color color(List<Slice> slices, int i) {
        return slices.get(i).key() == null ? OTHER : CATEGORICAL[i % CATEGORICAL.length];
    }

    /** Recorta el nombre con "…" para que entre en el ancho. */
    private static String fit(Graphics2D g, String s, double width) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= width) {
            return s;
        }
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > width) {
            t = t.substring(0, t.length() - 1);
        }
        return t.strip() + "…";
    }

    private static void centered(Graphics2D g, String s, double cx, double baselineY) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) baselineY);
    }

    private static Font font(int style, float size) {
        return new Font(Font.SANS_SERIF, style, 1).deriveFont(style, size);
    }

    /** Dibuja a 3x sobre fondo blanco y devuelve un data URI PNG. */
    private static String render(int w, int h, Consumer<Graphics2D> draw) {
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
