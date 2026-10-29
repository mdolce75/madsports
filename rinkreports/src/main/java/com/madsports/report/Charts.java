package com.madsports.report;

import com.madsports.player.PlayerReport;
import com.madsports.stats.ReportData.FoulSituation;
import com.madsports.stats.ReportData.MatchLine;
import com.madsports.stats.ReportData.PlayerLine;
import com.madsports.stats.ReportData.StandingSnapshot;
import com.madsports.stats.ReportData.RoundPosition;
import com.madsports.stats.ReportData.TeamRecord;
import com.madsports.stats.ReportData.TimeBucket;
import com.madsports.stats.ReportData.TimeProfile;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.madsports.report.ChartCanvas.*;
import static com.madsports.report.ChartForms.*;

/**
 * Gráficos del PDF (barras y donas), dibujados en Java2D como PNG y embebidos como data URI.
 * Reglas (guía de visualización): donas solo para parte-de-un-todo con pocas porciones (≤ 6); barras para comparar;
 * colores validados para daltonismo; cada gráfico lleva sus valores escritos (leyenda con cifras), así nunca
 * depende solo del color. Cada método devuelve {@code null} si no hay datos (la plantilla no muestra nada).
 */
public class Charts {

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

    /** Dona de ganados, empatados y perdidos a partir de los totales (informe de jugador). */
    public String resultsDonut(int won, int drawn, int lost) {
        return resultsDonut(new TeamRecord(won + drawn + lost, won, drawn, lost, 0, 0));
    }

    /** Dona de tarjetas por color (amarilla, azul, roja), con el color de cada tarjeta. */
    public String cardsDonut(int yellow, int blue, int red) {
        int total = yellow + blue + red;
        if (total == 0) {
            return null;
        }
        return donut(List.of(
                        new Slice("Amarillas", yellow, String.valueOf(yellow), CARD_YELLOW),
                        new Slice("Azules", blue, String.valueOf(blue), CARD_BLUE),
                        new Slice("Rojas", red, String.valueOf(red), CARD_RED)),
                String.valueOf(total), "tarjetas");
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
                (total / 60) + "'", "played");
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

    /**
     * Su puesto en la tabla después de cada fecha (1° arriba), en un torneo. {@code null} con menos de 2 fechas.
     */
    public String positionLine(StandingSnapshot s) {
        List<RoundPosition> ev = s == null ? null : s.evolution();
        if (ev == null || ev.size() < 2) {
            return null;
        }
        int w = 400;
        int h = 170;
        return render(w, h, g -> {
            int teams = Math.max(Math.max(2, s.groupSize()), ev.stream().mapToInt(RoundPosition::position).max().orElse(2));
            int top = 26;
            int bottom = h - 24;
            int left = 30;
            int right = w - 14;
            double stepY = (bottom - top) / (double) (teams - 1);
            double stepX = (right - left) / (double) (ev.size() - 1);
            g.setFont(font(Font.BOLD, 9));
            g.setColor(INK);
            g.drawString(s.competition() + " · puesto después de cada fecha", left, 13);
            g.setFont(font(Font.PLAIN, 8));
            for (int p : java.util.stream.IntStream.of(1, (teams + 1) / 2, teams).distinct().toArray()) {
                double y = top + (p - 1) * stepY;
                g.setColor(GRID);
                g.setStroke(new BasicStroke(1f));
                g.draw(new java.awt.geom.Line2D.Double(left, y, right, y));
                g.setColor(MUTED);
                g.drawString(p + "°", 4, (float) y + 3);
            }
            Path2D path = new Path2D.Double();
            for (int i = 0; i < ev.size(); i++) {
                double x = left + i * stepX;
                double y = top + (ev.get(i).position() - 1) * stepY;
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            g.setColor(CATEGORICAL[0]);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(path);
            int every = Math.max(1, (int) Math.ceil(ev.size() / 14.0));
            for (int i = 0; i < ev.size(); i++) {
                double x = left + i * stepX;
                double y = top + (ev.get(i).position() - 1) * stepY;
                g.setColor(SURFACE);
                g.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
                g.setColor(CATEGORICAL[0]);
                g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
                if (i % every == 0 || i == ev.size() - 1) {
                    g.setColor(MUTED);
                    centered(g, "F" + ev.get(i).round(), x, h - 8);
                }
            }
            RoundPosition last = ev.getLast();
            g.setColor(INK);
            g.setFont(font(Font.BOLD, 9));
            double ly = top + (last.position() - 1) * stepY;
            centered(g, last.position() + "°", right - 2, ly > top + 12 ? ly - 7 : ly + 15);
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
                OWN, RIVAL_HALF, "Faltas propias", "Faltas de los rivales");
    }

    // ---------- Informe de jugador ----------

    /** Goles del jugador por tramo de 5 minutos (una sola serie: el título del gráfico la nombra). */
    public String playerGoalsByBucket(List<PlayerReport.Bucket> buckets) {
        if (buckets == null || buckets.stream().mapToInt(PlayerReport.Bucket::goals).sum() == 0) {
            return null;
        }
        return stackedColumns(buckets.stream().map(PlayerReport.Bucket::label).toList(),
                new int[][]{buckets.stream().mapToInt(PlayerReport.Bucket::goals).toArray()},
                new Color[]{WIN}, null);
    }

    /** Tarjetas del jugador por tramo, apiladas por color: amarilla, azul y roja. */
    public String playerCardsByBucket(List<PlayerReport.Bucket> buckets) {
        if (buckets == null || buckets.stream().mapToInt(PlayerReport.Bucket::cards).sum() == 0) {
            return null;
        }
        return stackedColumns(buckets.stream().map(PlayerReport.Bucket::label).toList(), new int[][]{
                        buckets.stream().mapToInt(PlayerReport.Bucket::yellowCards).toArray(),
                        buckets.stream().mapToInt(PlayerReport.Bucket::blueCards).toArray(),
                        buckets.stream().mapToInt(PlayerReport.Bucket::redCards).toArray()},
                new Color[]{CARD_YELLOW, CARD_BLUE, CARD_RED}, new String[]{"Amarillas", "Azules", "Rojas"});
    }

    /**
     * Progresión de sus goles en cada torneo: goles acumulados después de cada partido que jugó en él, una línea por
     * torneo (los colores categóricos en orden; hasta 5 torneos, los primeros de la tabla). {@code null} sin goles.
     */
    public String goalProgression(List<PlayerReport.GoalProgression> series) {
        List<PlayerReport.GoalProgression> s = series == null ? List.of() : series.stream()
                .filter(p -> !p.cumulative().isEmpty()).limit(CATEGORICAL.length).toList();
        if (s.stream().allMatch(p -> p.total() == 0)) {
            return null;
        }
        int w = 400;
        int h = 200;
        return render(w, h, g -> {
            // Leyenda arriba, en tantos renglones como haga falta.
            g.setFont(font(Font.PLAIN, 9));
            FontMetrics fm = g.getFontMetrics();
            double lx = 30;
            double ly = 12;
            for (int i = 0; i < s.size(); i++) {
                String name = s.get(i).label();
                double width = 13 + fm.stringWidth(name) + 14;
                if (lx + width > w - 8 && lx > 30) {
                    lx = 30;
                    ly += 13;
                }
                g.setColor(CATEGORICAL[i]);
                g.fill(new java.awt.geom.RoundRectangle2D.Double(lx, ly - 8, 9, 9, 3, 3));
                g.setColor(INK);
                g.drawString(name, (float) (lx + 13), (float) ly);
                lx += width;
            }
            int maxGoals = Math.max(1, s.stream().mapToInt(PlayerReport.GoalProgression::total).max().orElse(1));
            int maxGames = s.stream().mapToInt(p -> p.cumulative().size()).max().orElse(1);
            double top = ly + 14;
            double bottom = h - 26;
            double left = 30;
            double right = w - 22;
            double unit = (bottom - top) / maxGoals;
            double stepX = (right - left) / maxGames;
            yTicks(g, left, right, bottom, unit, maxGoals, false);
            baseline(g, left, right, bottom);
            // Partidos: 1, 2, 3... (sin amontonar los números con muchos partidos).
            g.setColor(MUTED);
            g.setFont(font(Font.PLAIN, 8));
            int every = Math.max(1, (int) Math.ceil(maxGames / 12.0));
            for (int k = 1; k <= maxGames; k++) {
                if (k % every == 0 || k == 1 || k == maxGames) {
                    centered(g, String.valueOf(k), left + k * stepX, bottom + 11);
                }
            }
            g.drawString("Partidos jugados en el torneo →", (float) left, h - 4);
            for (int i = 0; i < s.size(); i++) {
                List<Integer> sums = s.get(i).cumulative();
                Path2D path = new Path2D.Double();
                path.moveTo(left, bottom);   // antes del primer partido: 0 goles
                for (int k = 0; k < sums.size(); k++) {
                    path.lineTo(left + (k + 1) * stepX, bottom - sums.get(k) * unit);
                }
                g.setColor(CATEGORICAL[i]);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(path);
                for (int k = 0; k < sums.size(); k++) {
                    double x = left + (k + 1) * stepX;
                    double y = bottom - sums.get(k) * unit;
                    g.setColor(SURFACE);
                    g.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
                    g.setColor(CATEGORICAL[i]);
                    g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
                }
                // El total del torneo al final de su línea.
                double ex = left + sums.size() * stepX;
                double ey = bottom - sums.getLast() * unit;
                g.setColor(INK);
                g.setFont(font(Font.BOLD, 9));
                g.drawString(String.valueOf(sums.getLast()), (float) (ex + 6), (float) (ey + 3));
            }
        });
    }

    /** Dona de convertidos y errados de penales o libres directos, con "convertidos/tirados" al centro. */
    public String setPieceDonut(int scored, int taken, String what) {
        if (taken <= 0) {
            return null;
        }
        int missed = Math.max(0, taken - scored);
        return donut(List.of(
                        new Slice("Convertidos", scored, String.valueOf(scored), WIN),
                        new Slice("Errados", missed, String.valueOf(missed), LOSS)),
                scored + "/" + taken, what);
    }
}
