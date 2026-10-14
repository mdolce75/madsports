package com.rinkmetrics.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.rinkmetrics.stats.ReportData;
import com.rinkmetrics.stats.ReportData.PlayerLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Valida el análisis que entrega la IA (resumen, fortalezas, debilidades, jugadores_destacados,
 * previa_proximo_rival, tendencia). Descarta jugadores que no existen en los datos.
 */
final class AnalysisValidator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisValidator.class);

    private AnalysisValidator() {
    }

    /** @return el análisis, o {@code null} si le faltan el resumen o las fortalezas/debilidades */
    static AnalysisResult validate(JsonNode in, ReportData data, String source) {
        if (in == null) {
            return null;
        }
        String summary = in.path("resumen").asText("").trim();
        List<String> strengths = strings(in.path("fortalezas"));
        List<String> weaknesses = strings(in.path("debilidades"));
        if (summary.isEmpty() || (strengths.isEmpty() && weaknesses.isEmpty())) {
            return null;
        }
        List<String> knownPlayers = data.teams().stream().flatMap(t -> t.players().stream())
                .map(PlayerLine::name).map(String::toLowerCase).toList();
        List<AnalysisResult.KeyPlayer> players = new ArrayList<>();
        for (JsonNode p : in.path("jugadores_destacados")) {
            String name = p.path("nombre").asText("").trim();
            if (knownPlayers.contains(name.toLowerCase())) {
                players.add(new AnalysisResult.KeyPlayer(name, p.path("motivo").asText("").trim()));
            } else {
                log.info("Se descarta jugador destacado inexistente en los datos: {}", name);
            }
        }
        return new AnalysisResult(source, summary, strengths, weaknesses, players,
                in.path("previa_proximo_rival").asText("").trim(), in.path("tendencia").asText("").trim(),
                strings(in.path("analisis_faltas")));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> {
            String s = n.asText("").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        });
        return out;
    }
}
