package com.madsports.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.madsports.stats.ReportData;
import com.madsports.stats.ReportData.TeamReport;

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
        o.put("category", data.category());
        o.put("period", data.tournament());
        if (data.partial()) {
            o.put("period_note", "Récord, resultados, rivales, time_profile y las tarjetas de disciplina cubren "
                    + "solo los últimos " + data.lastMatches() + " partidos de cada equipo (tarjetas contadas en sus fichas). "
                    + "players y la pelota parada son del torneo completo.");
        }
        o.put("report_date", data.generatedOn());
        o.put("teams", data.teams().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_code", t.code());
            m.put("name", t.name());
            m.put("total", t.total());
            m.put("points", t.total().points());
            m.put("effectiveness_pct", round(t.total().effectiveness()));
            m.put("home", t.home());
            m.put("away", t.away());
            m.put("last_results", t.form());
            m.put("standings", t.standings());
            m.put("by_tournament", t.byTournament());
            m.put("biggest_win", t.biggestWin());
            m.put("worst_loss", t.worstLoss());
            m.put("strengths_weaknesses", t.indicators());
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
                    "set_pieces", Map.of(
                            "direct_free_hits", t.setPieces().directFreeScored() + "/" + t.setPieces().directFreeTaken(),
                            "direct_free_hits_pct", round(t.setPieces().directFreePct()),
                            "penalties", t.setPieces().penaltiesScored() + "/" + t.setPieces().penaltiesTaken(),
                            "penalties_pct", round(t.setPieces().penaltyPct())),
                    "discipline", t.discipline(),
                    "fouls", t.fouls());
            case "rivals" -> t.rivals();
            case "time_profile" -> Map.of(
                    "profile", t.timeProfile(),
                    "short_handed_minutes_per_game", round(t.timeProfile().shortHandedMinutesPerGame()),
                    "power_play_minutes_per_game", round(t.timeProfile().powerPlayMinutesPerGame()));
            default -> throw new IllegalArgumentException("section inválida: " + section + ". Válidas: " + SECTIONS);
        };
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
