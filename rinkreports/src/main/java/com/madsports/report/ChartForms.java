package com.madsports.report;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

import static com.madsports.report.ChartCanvas.*;

/** Las formas genéricas de los gráficos del PDF: la dona con su leyenda y las columnas (divergentes, agrupadas, apiladas). */
final class ChartForms {

    private ChartForms() {
    }

    record Slice(String label, double value, String valueText, Color color) {
    }


    static String donut(List<Slice> slices, String centerBig, String centerSmall) {
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

    static String divergingColumns(List<String> labels, int[] up, int[] down, Color upColor, Color downColor,
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

    static String groupedColumns(List<String> labels, int[] a, int[] b, Color ca, Color cb, String na, String nb) {
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

    /**
     * Columnas apiladas por tramo (una serie por color), con el total arriba de cada columna. Entre segmentos queda una
     * separación de 2px del color de fondo. Con una sola serie no lleva leyenda.
     */
    static String stackedColumns(List<String> labels, int[][] series, Color[] colors, String[] names) {
        int w = 520;
        int h = 190;
        int[] totals = new int[labels.size()];
        for (int[] s : series) {
            for (int i = 0; i < s.length; i++) {
                totals[i] += s[i];
            }
        }
        return render(w, h, g -> {
            int max = Math.max(1, max(totals));
            int top = names == null ? 18 : 30;
            int bottom = h - 26;
            int left = 30;
            int right = w - 8;
            double unit = (bottom - top - 12.0) / max;
            if (names != null) {
                legend(g, left, 14, names, colors);
            }
            yTicks(g, left, right, bottom, unit, max, false);
            double slot = (double) (right - left) / labels.size();
            double bw = Math.min(26, slot - 8);
            g.setFont(font(Font.BOLD, 8));
            for (int i = 0; i < labels.size(); i++) {
                double x = left + i * slot + (slot - bw) / 2;
                double y = bottom;
                int last = -1;
                for (int k = 0; k < series.length; k++) {
                    if (series[k][i] > 0) {
                        last = k;
                    }
                }
                for (int k = 0; k < series.length; k++) {
                    int v = series[k][i];
                    if (v <= 0) {
                        continue;
                    }
                    double hgt = v * unit;
                    if (k == last) {
                        bar(g, x, y - hgt, bw, hgt, colors[k], true);
                    } else {
                        g.setColor(colors[k]);
                        g.fill(new Rectangle2D.Double(x, y - hgt + 2, bw, Math.max(0, hgt - 2)));
                    }
                    y -= hgt;
                }
                if (totals[i] > 0) {
                    g.setColor(INK);
                    centered(g, String.valueOf(totals[i]), x + bw / 2, y - 3);
                }
            }
            baseline(g, left, right, bottom);
            xLabels(g, labels, left, slot, h - 8);
            periodSeparator(g, labels.size(), left, slot, top, bottom);
        });
    }
}
