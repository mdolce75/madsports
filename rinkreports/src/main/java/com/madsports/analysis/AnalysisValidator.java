package com.madsports.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.madsports.stats.ReportData;
import com.madsports.stats.ReportData.PlayerLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Valida el análisis que entrega la IA (summary, strengths, weaknesses, key_players,
 * next_opponent_preview, trend). Descarta jugadores que no existen en los datos.
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
        String summary = in.path("summary").asText("").trim();
        List<String> strengths = strings(in.path("strengths"));
        List<String> weaknesses = strings(in.path("weaknesses"));
        if (summary.isEmpty() || (strengths.isEmpty() && weaknesses.isEmpty())) {
            return null;
        }
        List<String> knownPlayers = data.teams().stream().flatMap(t -> t.players().stream())
                .map(PlayerLine::name).map(String::toLowerCase).toList();
        List<AnalysisResult.KeyPlayer> players = new ArrayList<>();
        for (JsonNode p : in.path("key_players")) {
            String name = p.path("name").asText("").trim();
            if (knownPlayers.contains(name.toLowerCase())) {
                players.add(new AnalysisResult.KeyPlayer(name, p.path("reason").asText("").trim()));
            } else {
                log.info("Se descarta jugador destacado inexistente en los datos: {}", name);
            }
        }
        return new AnalysisResult(source, summary, strengths, weaknesses, players,
                in.path("next_opponent_preview").asText("").trim(), in.path("trend").asText("").trim(),
                strings(in.path("fouls_analysis")));
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
