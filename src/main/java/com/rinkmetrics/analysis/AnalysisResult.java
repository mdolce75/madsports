package com.rinkmetrics.analysis;

import java.util.List;

/**
 * Texto de análisis que acompaña las cifras del informe.
 *
 * @param source        "Gemini (modelo)" o "Reglas" — se imprime en el PDF para que quede claro quién lo escribió
 * @param foulsAnalysis análisis escrito de las faltas, en párrafos
 */
public record AnalysisResult(String source, String summary, List<String> strengths, List<String> weaknesses,
                             List<KeyPlayer> keyPlayers, String nextOpponentPreview, String trend,
                             List<String> foulsAnalysis) {

    /** Copia con otro análisis de faltas (p. ej. el de reglas si la IA no lo entregó). */
    public AnalysisResult withFoulsAnalysis(List<String> paragraphs) {
        return new AnalysisResult(source, summary, strengths, weaknesses, keyPlayers, nextOpponentPreview, trend,
                List.copyOf(paragraphs));
    }

    public record KeyPlayer(String name, String reason) {
    }
}
