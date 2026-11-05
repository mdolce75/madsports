package com.madsports.report;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Nombres de los archivos de los informes: {@code RinkReports-Rama-Club-Categoria...}. Cada parte va sin acentos, sin
 * espacios y con mayúscula inicial en cada palabra ("Infantil B" → "InfantilB"), así el guion separa solo las partes.
 */
public final class FileNames {

    private FileNames() {
    }

    /**
     * "RinkReports-Femenino-CAVS-Primera.pdf": las partes ya armadas con {@link #part} o {@link #club} (las vacías no
     * van).
     */
    public static String of(String extension, String... parts) {
        return "RinkReports-" + Arrays.stream(parts).filter(Objects::nonNull).filter(p -> !p.isEmpty())
                .collect(Collectors.joining("-")) + "." + extension;
    }

    /** Una palabra o frase como parte del nombre: "Pérez Barbosa" → "PerezBarbosa". */
    public static String part(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return Arrays.stream(n.split("[^A-Za-z0-9]+")).filter(w -> !w.isEmpty())
                .map(w -> w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining());
    }

    /** El código del club tal cual, sin lo que no sea letra o número ("CAVS"). */
    public static String club(String code) {
        String n = Normalizer.normalize(code == null ? "" : code, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^A-Za-z0-9]+", "");
    }
}
