package com.fppstats.analysis;

import java.util.List;

/**
 * Texto de análisis que acompaña las cifras del informe.
 *
 * @param source "Claude (modelo)" o "Reglas" — se imprime en el PDF para que quede claro quién lo escribió
 */
public record AnalysisResult(String source, String summary, List<String> strengths, List<String> weaknesses,
                             List<KeyPlayer> keyPlayers, String nextOpponentPreview, String trend) {

    public record KeyPlayer(String name, String reason) {
    }
}
