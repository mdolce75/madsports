package com.fppstats.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fppstats.stats.ReportData;
import com.fppstats.stats.ReportData.TeamReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Herramientas de solo lectura que el agente usa para consultar las cifras ya calculadas.
 * Devuelven JSON; un error de uso se informa como texto para que el modelo se corrija.
 */
class AnalysisTools {

    static final String OVERVIEW = "get_overview";
    static final String TEAM_DETAIL = "get_team_detail";
    static final String LEAGUE = "get_league_context";
    static final String SUBMIT = "submit_analysis";

    static final List<String> SECTIONS = List.of("results", "upcoming", "players", "set_pieces_discipline", "rivals",
            "time_profile");

    private final ReportData data;
    private final ObjectMapper mapper;

    AnalysisTools(ReportData data, ObjectMapper mapper) {
        this.data = data;
        this.mapper = mapper;
    }

    /** Ejecuta una herramienta de lectura. Lanza {@link IllegalArgumentException} si la entrada es inválida. */
    String run(String name, JsonNode input) throws Exception {
        return switch (name) {
            case OVERVIEW -> mapper.writeValueAsString(overview());
            case LEAGUE -> mapper.writeValueAsString(data.league());
            case TEAM_DETAIL -> mapper.writeValueAsString(teamDetail(
                    text(input, "team_code"), text(input, "section")));
            default -> throw new IllegalArgumentException("Herramienta desconocida: " + name);
        };
    }

    private Map<String, Object> overview() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("club", data.club());
        o.put("categoria", data.category());
        o.put("periodo", data.torneo());
        if (data.partial()) {
            o.put("aclaracion_periodo", "Récord, resultados, rivales, time_profile y las tarjetas de disciplina cubren "
                    + "solo los últimos " + data.lastMatches() + " partidos de cada equipo (tarjetas contadas en sus fichas). "
                    + "players y la pelota parada son del torneo completo.");
        }
        o.put("fecha_informe", data.generatedOn());
        o.put("equipos", data.teams().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_code", t.code());
            m.put("nombre", t.name());
            m.put("total", t.total());
            m.put("puntos", t.total().points());
            m.put("efectividad_pct", round(t.total().effectiveness()));
            m.put("local", t.home());
            m.put("visitante", t.away());
            m.put("ultimos_resultados", t.form());
            m.put("posiciones", t.standings());
            m.put("por_torneo", t.byTorneo());
            m.put("mayor_victoria", t.biggestWin());
            m.put("peor_derrota", t.worstLoss());
            return m;
        }).toList());
        return o;
    }

    private Object teamDetail(String code, String section) {
        TeamReport t = data.teams().stream().filter(x -> x.code().equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("team_code inexistente: " + code + ". Válidos: "
                        + data.teams().stream().map(TeamReport::code).toList()));
        return switch (section) {
            case "results" -> t.results();
            case "upcoming" -> t.upcoming();
            case "players" -> t.players();
            case "set_pieces_discipline" -> Map.of(
                    "pelota_parada", Map.of(
                            "faltas_directas", t.setPieces().directFreeScored() + "/" + t.setPieces().directFreeTaken(),
                            "faltas_directas_pct", round(t.setPieces().directFreePct()),
                            "penales", t.setPieces().penaltiesScored() + "/" + t.setPieces().penaltiesTaken(),
                            "penales_pct", round(t.setPieces().penaltyPct())),
                    "disciplina", t.discipline());
            case "rivals" -> t.rivals();
            case "time_profile" -> t.timeProfile();
            default -> throw new IllegalArgumentException("section inválida: " + section + ". Válidas: " + SECTIONS);
        };
    }

    private static String text(JsonNode input, String field) {
        JsonNode n = input == null ? null : input.get(field);
        if (n == null || !n.isTextual()) {
            throw new IllegalArgumentException("Falta el campo '" + field + "'");
        }
        return n.asText();
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
