package com.madsports.pdf;

import com.madsports.stats.ReportData;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formateo usado desde la plantilla. */
public class Format {
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

    /** Minutos con decimales como reloj: 3.25 -> "3:15". */
    public String clock(double minutes) {
        long seconds = Math.round(minutes * 60);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** Valor de un indicador del análisis de fortalezas y debilidades, según su unidad. */
    public String indicator(double value, ReportData.Unit unit) {
        return switch (unit) {
            case PER_GAME -> dec(value);
            case PERCENT -> pct(value);
            case MINUTES -> clock(value);
            case DIFF -> String.format(Locale.forLanguageTag("es-AR"), "%+.2f", value);
        };
    }

    /** Número con signo: +1,50 / -0,50 / 0,00. */
    public String signed(double v) {
        return Math.abs(v) < 0.005 ? "0,00" : String.format(Locale.forLanguageTag("es-AR"), "%+.2f", v);
    }

    /** Porcentaje de partidos: 3 de 4 -> "75%". */
    public String share(int part, int total) {
        return total == 0 ? "-" : String.format(Locale.forLanguageTag("es-AR"), "%.0f%%", 100.0 * part / total);
    }

    public String fraction(int scored, int taken) {
        return taken == 0 ? "-" : scored + "/" + taken;
    }
}
