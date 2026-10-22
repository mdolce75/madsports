package com.madsports.dt;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Períodos de los informes del DT: cada partido cae en un mes ("2026-10"), un bimestre ("2026-5": septiembre y
 * octubre), un semestre ("2026-2": julio a diciembre) y un campeonato (el que se cargó al crearlo; "" si no tiene).
 */
public enum Periodo {
    MES, BIMESTRE, SEMESTRE, CAMPEONATO;

    private static final Locale ES = Locale.forLanguageTag("es-AR");
    private static final String[] ORDINAL = {"", "1.º", "2.º", "3.º", "4.º", "5.º", "6.º"};

    /** Valor del período en el que cae el partido. */
    public String valor(LocalDate fecha, String torneo) {
        return switch (this) {
            case MES -> String.format(Locale.ROOT, "%d-%02d", fecha.getYear(), fecha.getMonthValue());
            case BIMESTRE -> fecha.getYear() + "-" + ((fecha.getMonthValue() + 1) / 2);
            case SEMESTRE -> fecha.getYear() + "-" + (fecha.getMonthValue() <= 6 ? 1 : 2);
            case CAMPEONATO -> torneo == null ? "" : torneo;
        };
    }

    /** Nombre para mostrar: "Octubre 2026", "Bimestre septiembre-octubre 2026", "2.º semestre 2026". */
    public String nombre(String valor) {
        if (this == CAMPEONATO) {
            return valor == null || valor.isBlank() ? "Sin campeonato" : valor;
        }
        String[] partes = valor == null ? new String[0] : valor.split("-");
        try {
            int anio = Integer.parseInt(partes[0]);
            int n = Integer.parseInt(partes[1]);
            return switch (this) {
                case MES -> capital(mes(n)) + " " + anio;
                case BIMESTRE -> {
                    if (n < 1 || n > 6) throw new IllegalArgumentException();
                    yield "Bimestre " + mes(2 * n - 1) + "-" + mes(2 * n) + " " + anio;
                }
                case SEMESTRE -> {
                    if (n < 1 || n > 2) throw new IllegalArgumentException();
                    yield ORDINAL[n] + " semestre " + anio + (n == 1 ? " (enero a junio)" : " (julio a diciembre)");
                }
                default -> throw new IllegalStateException();
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("El período " + valor + " no es válido.");
        }
    }

    private static String mes(int n) {
        return Month.of(n).getDisplayName(TextStyle.FULL, ES);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
