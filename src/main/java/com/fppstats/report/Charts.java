package com.fppstats.report;

import com.fppstats.stats.ReportData.FoulSituation;
import com.fppstats.stats.ReportData.MatchLine;
import com.fppstats.stats.ReportData.PlayerLine;
import com.fppstats.stats.ReportData.TeamRecord;
import com.fppstats.stats.ReportData.TimeBucket;
import com.fppstats.stats.ReportData.TimeProfile;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Gráficos del PDF (barras y donas), dibujados en Java2D como PNG y embebidos como data URI.
 * Reglas (guía de visualización): donas solo para parte-de-un-todo con pocas porciones (≤ 6); barras para comparar;
 * colores validados para daltonismo; cada gráfico lleva sus valores escritos (leyenda con cifras), así nunca
 * depende solo del color. Cada método devuelve {@code null} si no hay datos (la plantilla no muestra nada).
 */
public class Charts {

    /** Se dibuja a 3x para que se vea nítido impreso. */
    private static final int SCALE = 3;
    private static final Locale ES = Locale.forLanguageTag("es-AR");

    // Paleta (validada con scripts/validate_palette.js de la guía de visualización)
    static final Color WIN = new Color(0x2f855a);
    static final Color DRAW = new Color(0xd69e2e);
    static final Color LOSS = new Color(0xc53030);
    static final Color[] CATEGORICAL = {new Color(0x2a78d6), new Color(0xeb6834), new Color(0x1baf7a),
            new Color(0xeda100), new Color(0xe87ba4)};
    static final Color OTHER = new Color(0xa0aec0);
    static final Color OWN = new Color(0xff5a1f);
    static final Color RIVAL = new Color(0x121a2a);
    private static final Color INK = new Color(0x1d2433);
    private static final Color MUTED = new Color(0x52514e);
    private static final Color GRID = new Color(0xe2e8f0);
    private static final Color SURFACE = Color.WHITE;

    record Slice(String label, double value, String valueText, Color color) {
    }

    // ---------- Gráficos del informe ----------

    /** Dona de resultados: ganados, empatados y perdidos. */
    public String resultsDonut(TeamRecord r) {
        if (r == null || r.played() == 0) {
            return null;
        }
        return donut(List.of(
                        new Slice("Ganados", r.won(), String.valueOf(r.won()), WIN),
                        new Slice("Empatados", r.drawn(), String.valueOf(r.drawn()), DRAW),
                        new Slice("Perdidos", r.lost(), String.valueOf(r.lost()), LOSS)),
                String.valueOf(r.played()), "partidos");
    }

    /** Dona del reparto del gol: los 5 goleadores y el resto en "Otros". */
    public String scorersDonut(List<PlayerLine> players) {
        List<PlayerLine> scorers = players.stream().filter(p -> p.goals() > 0)
                .sorted(Comparator.comparingInt(PlayerLine::goals).reversed()).toList();
        int total = scorers.stream().mapToInt(PlayerLine::goals).sum();
        if (total == 0) {
            return null;
        }
        List<Slice> slices = new ArrayList<>();
        for (int i = 0; i < Math.min(5, scorers.size()); i++) {
            PlayerLine p = scorers.get(i);
            slices.add(new Slice(shortName(p.name()), p.goals(), String.valueOf(p.goals()), CATEGORICAL[i]));
        }
        int others = scorers.stream().skip(5).mapToInt(PlayerLine::goals).sum();
        if (others > 0) {
            slices.add(new Slice("Otros (" + (scorers.size() - 5) + ")", others, String.valueOf(others), OTHER));
        }
        return donut(slices, String.valueOf(total), "goles");
    }

    /** Dona del tiempo jugado ganando, empatando y perdiendo (fichas). */
    public String situationDonut(FoulSituation s) {
        if (s == null || s.secondsAhead() + s.secondsLevel() + s.secondsBehind() == 0) {
            return null;
        }
        int total = s.secondsAhead() + s.secondsLevel() + s.secondsBehind();
        return donut(List.of(
                        new Slice("Ganando", s.secondsAhead(), clock(s.secondsAhead()), WIN),
                        new Slice("Empatando", s.secondsLevel(), clock(s.secondsLevel()), DRAW),
                        new Slice("Perdiendo", s.secondsBehind(), clock(s.secondsBehind()), LOSS)),
                (total / 60) + "'", "jugados");
    }

    /** Barras partido a partido: diferencia de gol (arriba ganó, abajo perdió). */
    public String matchDiffBars(List<MatchLine> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        int w = 400;
        int h = 190;
        return render(w, h, g -> {
            int maxAbs = Math.max(1, results.stream().mapToInt(m -> Math.abs(m.goalsFor() - m.goalsAgainst())).max().orElse(1));
            int top = 32;
            int bottom = h - 24;
            int left = 30;
            int right = w - 8;
            double zero = top + (bottom - top) / 2.0;
            double unit = (bottom - top) / 2.0 / maxAbs;
            legend(g, left, 14, new String[]{"Ganó", "Empató", "Perdió"}, new Color[]{WIN, DRAW, LOSS});
            yTicks(g, left, right, zero, unit, maxAbs, true);
            double slot = (double) (right - left) / results.size();
            double bw = Math.max(3, slot - 2);
            for (int i = 0; i < results.size(); i++) {
                MatchLine m = results.get(i);
                int d = m.goalsFor() - m.goalsAgainst();
                double x = left + i * slot + (slot - bw) / 2;
                Color c = d > 0 ? WIN : d < 0 ? LOSS : DRAW;
                double hh = d == 0 ? 3 : Math.abs(d) * unit;
                bar(g, x, d >= 0 ? zero - hh : zero, bw, hh, c, d >= 0);
            }
            baseline(g, left, right, zero);
            g.setColor(MUTED);
            g.setFont(font(Font.PLAIN, 8));
            g.drawString("Partidos en orden cronológico →", left, h - 5);
        });
    }

    /** Columnas por tramo de 5': goles a favor hacia arriba y en contra hacia abajo. */
    public String goalsByBucket(TimeProfile tp) {
        if (tp == null || !tp.hasData()) {
            return null;
        }
        List<TimeBucket> b = tp.buckets();
        int[] up = b.stream().mapToInt(TimeBucket::goalsFor).toArray();
        int[] down = b.stream().mapToInt(TimeBucket::goalsAgainst).toArray();
        return divergingColumns(b.stream().map(TimeBucket::label).toList(), up, down, WIN, LOSS,
                "Goles a favor", "Goles en contra");
    }

    /** Columnas agrupadas por tramo de 5': faltas propias y de los rivales. */
    public String foulsByBucket(TimeProfile tp) {
        if (tp == null || !tp.hasData()) {
            return null;
        }
        List<TimeBucket> b = tp.buckets();
        if (b.stream().mapToInt(x -> x.foulsOwn() + x.foulsRival()).sum() == 0) {
            return null;
        }
        return groupedColumns(b.stream().map(TimeBucket::label).toList(),
                b.stream().mapToInt(TimeBucket::foulsOwn).toArray(),
                b.stream().mapToInt(TimeBucket::foulsRival).toArray(),
                OWN, RIVAL, "Faltas propias", "Faltas de los rivales");
    }

    // ---------- Formas genéricas ----------

    String donut(List<Slice> slices, String centerBig, String centerSmall) {
        double total = slices.stream().mapToDouble(Slice::value).sum();
        if (total <= 0) {
            return null;
        }
        int w = 410;
        int h = 160;
        return render(w, h, g -> {
            double size = 130;
            double cx = 10;
            double cy = (h - size) / 2;
            double thickness = 30;
            double start = 90;
            for (Slice s : slices) {
                if (s.value() <= 0) {
                    continue;
                }
                double extent = -360 * s.value() / total;
                g.setColor(s.color());
                g.fill(new Arc2D.Double(cx, cy, size, size, start, extent, Arc2D.PIE));
                start += extent;
            }
            // Separadores de 2px entre porciones y agujero central
            g.setColor(SURFACE);
            g.setStroke(new BasicStroke(2f * 1));
            start = 90;
            if (slices.stream().filter(s -> s.value() > 0).count() > 1) {
                for (Slice s : slices) {
                    if (s.value() <= 0) {
                        continue;
                    }
                    double a = Math.toRadians(start);
                    g.draw(new java.awt.geom.Line2D.Double(cx + size / 2, cy + size / 2,
                            cx + size / 2 + Math.cos(a) * size / 2, cy + size / 2 - Math.sin(a) * size / 2));
                    start += -360 * s.value() / total;
                }
            }
            g.fill(new Ellipse2D.Double(cx + thickness, cy + thickness, size - 2 * thickness, size - 2 * thickness));
            // Centro
            g.setColor(INK);
            g.setFont(font(Font.BOLD, 17));
            centered(g, centerBig, cx + size / 2, cy + size / 2 + 3);
            g.setColor(MUTED);
            g.setFont(font(Font.PLAIN, 8));
            centered(g, centerSmall, cx + size / 2, cy + size / 2 + 17);
            // Leyenda con valores y porcentaje
            double lx = cx + size + 18;
            double ly = h / 2.0 - slices.size() * 20 / 2.0 + 12;
            for (Slice s : slices) {
                g.setColor(s.color());
                g.fill(new java.awt.geom.RoundRectangle2D.Double(lx, ly - 9, 10, 10, 3, 3));
                g.setColor(INK);
                g.setFont(font(Font.PLAIN, 10));
                g.drawString(s.label(), (float) (lx + 16), (float) ly);
                String v = s.valueText() + "  " + String.format(ES, "%.0f%%", 100 * s.value() / total);
                g.setFont(font(Font.BOLD, 10));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(v, (float) (w - 6 - fm.stringWidth(v)), (float) ly);
                ly += 20;
            }
        });
    }

    String divergingColumns(List<String> labels, int[] up, int[] down, Color upColor, Color downColor,
                            String upName, String downName) {
        int w = 520;
        int h = 210;
        return render(w, h, g -> {
            int maxAbs = Math.max(1, Math.max(max(up), max(down)));
            int top = 30;
            int bottom = h - 26;
            int left = 30;
            int right = w - 8;
            double zero = top + (bottom - top) / 2.0;
            double unit = ((bottom - top) / 2.0 - 10) / maxAbs;
            legend(g, left, 14, new String[]{upName, downName}, new Color[]{upColor, downColor});
            yTicks(g, left, right, zero, unit, maxAbs, true);
            double slot = (double) (right - left) / labels.size();
            double bw = Math.min(26, slot - 6);
            g.setFont(font(Font.BOLD, 8));
            for (int i = 0; i < labels.size(); i++) {
                double x = left + i * slot + (slot - bw) / 2;
                if (up[i] > 0) {
                    bar(g, x, zero - up[i] * unit - 1, bw, up[i] * unit, upColor, true);
                    g.setColor(INK);
                    centered(g, String.valueOf(up[i]), x + bw / 2, zero - up[i] * unit - 4);
                }
                if (down[i] > 0) {
                    bar(g, x, zero + 1, bw, down[i] * unit, downColor, false);
                    g.setColor(INK);
                    centered(g, String.valueOf(down[i]), x + bw / 2, zero + down[i] * unit + 11);
                }
            }
            baseline(g, left, right, zero);
            xLabels(g, labels, left, slot, h - 8);
            periodSeparator(g, labels.size(), left, slot, top, bottom);
        });
    }

    String groupedColumns(List<String> labels, int[] a, int[] b, Color ca, Color cb, String na, String nb) {
        int w = 520;
        int h = 190;
        return render(w, h, g -> {
            int max = Math.max(1, Math.max(max(a), max(b)));
            int top = 30;
            int bottom = h - 26;
            int left = 30;
            int right = w - 8;
            double unit = (bottom - top - 12.0) / max;
            legend(g, left, 14, new String[]{na, nb}, new Color[]{ca, cb});
            yTicks(g, left, right, bottom, unit, max, false);
            double slot = (double) (right - left) / labels.size();
            double bw = Math.min(14, (slot - 8) / 2);
            g.setFont(font(Font.BOLD, 8));
            for (int i = 0; i < labels.size(); i++) {
                double x = left + i * slot + (slot - 2 * bw - 2) / 2;
                int[] vals = {a[i], b[i]};
                Color[] cols = {ca, cb};
                for (int k = 0; k < 2; k++) {
                    double bx = x + k * (bw + 2);
                    if (vals[k] > 0) {
                        bar(g, bx, bottom - vals[k] * unit, bw, vals[k] * unit, cols[k], true);
                        g.setColor(INK);
                        centered(g, String.valueOf(vals[k]), bx + bw / 2, bottom - vals[k] * unit - 3);
                    }
                }
            }
            baseline(g, left, right, bottom);
            xLabels(g, labels, left, slot, h - 8);
            periodSeparator(g, labels.size(), left, slot, top, bottom);
        });
    }

    // ---------- Piezas ----------

    /** Barra con el extremo de datos redondeado (4px) y el lado de la base recto. */
    private static void bar(Graphics2D g, double x, double y, double w, double h, Color c, boolean roundTop) {
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

    private static void legend(Graphics2D g, double x, double y, String[] names, Color[] colors) {
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
    private static void yTicks(Graphics2D g, double left, double right, double zero, double unit, int max,
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

    private static void baseline(Graphics2D g, double left, double right, double y) {
        g.setColor(new Color(0x94a3b8));
        g.setStroke(new BasicStroke(1f));
        g.draw(new java.awt.geom.Line2D.Double(left, y, right, y));
    }

    /** Etiquetas del eje x: solo los minutos ("0-5'"), con 1T / 2T una vez por tiempo. */
    private static void xLabels(Graphics2D g, List<String> labels, double left, double slot, double y) {
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
    private static void periodSeparator(Graphics2D g, int n, double left, double slot, double top, double bottom) {
        if (n % 2 != 0) {
            return;
        }
        double x = left + (n / 2.0) * slot;
        g.setColor(new Color(0x94a3b8));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
        g.draw(new java.awt.geom.Line2D.Double(x, top - 6, x, bottom));
        g.setStroke(new BasicStroke(1f));
    }

    private static void centered(Graphics2D g, String s, double cx, double baselineY) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) baselineY);
    }

    /** Los gráficos se muestran achicados en media columna: la letra se agranda un 30% para que se lea impresa. */
    private static final float FONT_SCALE = 1.3f;

    private static Font font(int style, float size) {
        return new Font(Font.SANS_SERIF, style, 1).deriveFont(style, size * FONT_SCALE);
    }

    private static int max(int[] v) {
        int m = 0;
        for (int x : v) {
            m = Math.max(m, x);
        }
        return m;
    }

    private static String clock(int seconds) {
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
