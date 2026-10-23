package com.madsports.coach;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Períodos de los informes del DT: cada partido cae en un mes ("2026-10"), un bimestre ("2026-5": septiembre y
 * octubre), un semestre ("2026-2": julio a diciembre), un año ("2026": todos los informes del año juntos) y un
 * campeonato (el que se cargó al crearlo; "" si no tiene).
 */
public enum ReportPeriod {
    MONTH, TWO_MONTHS, SEMESTER, YEAR, CHAMPIONSHIP;

    private static final Locale ES = Locale.forLanguageTag("es-AR");
    private static final String[] ORDINAL = {"", "1.º", "2.º", "3.º", "4.º", "5.º", "6.º"};

    /** Valor del período en el que cae el partido. */
    public String value(LocalDate date, String tournament) {
        return switch (this) {
            case MONTH -> String.format(Locale.ROOT, "%d-%02d", date.getYear(), date.getMonthValue());
            case TWO_MONTHS -> date.getYear() + "-" + ((date.getMonthValue() + 1) / 2);
            case SEMESTER -> date.getYear() + "-" + (date.getMonthValue() <= 6 ? 1 : 2);
            case YEAR -> String.valueOf(date.getYear());
            case CHAMPIONSHIP -> tournament == null ? "" : tournament;
        };
    }

    /** Nombre para mostrar: "Octubre 2026", "Bimestre septiembre-octubre 2026", "2.º semestre 2026", "Año 2026". */
    public String name(String value) {
        if (this == CHAMPIONSHIP) {
            return value == null || value.isBlank() ? "Sin campeonato" : value;
        }
        if (this == YEAR) {
            try {
                return "Año " + Integer.parseInt(value == null ? "" : value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("El período " + value + " no es válido.");
            }
        }
        String[] parts = value == null ? new String[0] : value.split("-");
        try {
            int year = Integer.parseInt(parts[0]);
            int n = Integer.parseInt(parts[1]);
            return switch (this) {
                case MONTH -> capitalize(month(n)) + " " + year;
                case TWO_MONTHS -> {
                    if (n < 1 || n > 6) throw new IllegalArgumentException();
                    yield "Bimestre " + month(2 * n - 1) + "-" + month(2 * n) + " " + year;
                }
                case SEMESTER -> {
                    if (n < 1 || n > 2) throw new IllegalArgumentException();
                    yield ORDINAL[n] + " semestre " + year + (n == 1 ? " (enero a junio)" : " (julio a diciembre)");
                }
                default -> throw new IllegalStateException();
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("El período " + value + " no es válido.");
        }
    }

    private static String month(int n) {
        return Month.of(n).getDisplayName(TextStyle.FULL, ES);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
