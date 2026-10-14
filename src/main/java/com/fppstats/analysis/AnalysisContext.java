package com.fppstats.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fppstats.stats.ReportData;
import com.fppstats.stats.ReportData.TeamReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Arma el JSON con las cifras ya calculadas que recibe la IA para redactar el análisis. */
class AnalysisContext {

    static final List<String> SECTIONS = List.of("results", "upcoming", "players", "set_pieces_discipline", "rivals",
            "time_profile");

    private final ReportData data;
    private final ObjectMapper mapper;

    AnalysisContext(ReportData data, ObjectMapper mapper) {
        this.data = data;
        this.mapper = mapper;
    }

    /** Todos los datos: overview, league_context y team_detail por equipo. */
    String fullContext() throws Exception {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("overview", overview());
        all.put("league_context", data.league());
        Map<String, Object> teams = new LinkedHashMap<>();
        for (TeamReport t : data.teams()) {
            Map<String, Object> sections = new LinkedHashMap<>();
            for (String section : SECTIONS) {
                sections.put(section, teamDetail(t.code(), section));
            }
            teams.put(t.code(), sections);
        }
        all.put("team_detail", teams);
        return mapper.writeValueAsString(all);
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
            m.put("fortalezas_debilidades", t.indicators());
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
            case "time_profile" -> Map.of(
                    "perfil", t.timeProfile(),
                    "minutos_con_uno_menos_por_partido", round(t.timeProfile().shortHandedMinutesPerGame()),
                    "minutos_con_uno_mas_por_partido", round(t.timeProfile().powerPlayMinutesPerGame()));
            default -> throw new IllegalArgumentException("section inválida: " + section + ". Válidas: " + SECTIONS);
        };
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
