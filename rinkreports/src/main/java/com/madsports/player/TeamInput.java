package com.madsports.player;

import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.SeasonMatch;

import java.util.List;
import java.util.Map;

/**
 * Lo leído de SIDGAD para un equipo del club en una competición: sus partidos jugados, su planilla en cada uno y
 * los eventos del acta (goles y tarjetas con minuto e {@code id_player}).
 *
 * @param summaries      partidos sin acta en que figura el jugador, según su ficha de temporada (por idp)
 * @param summaryLoaded  se pudo leer su ficha de temporada completa: un partido sin acta que no figura ahí no lo jugó
 */
record TeamInput(Competition competition, String category, int teamId, List<Match> matches,
                 Map<Integer, List<LineupPlayer>> sheets, Map<Integer, List<MatchEvent>> events,
                 Map<Integer, SeasonMatch> summaries, boolean summaryLoaded) {

    TeamInput(Competition competition, String category, int teamId, List<Match> matches,
              Map<Integer, List<LineupPlayer>> sheets, Map<Integer, List<MatchEvent>> events) {
        this(competition, category, teamId, matches, sheets, events, Map.of(), false);
    }

    TeamInput(Competition competition, String category, int teamId, List<Match> matches,
              Map<Integer, List<LineupPlayer>> sheets) {
        this(competition, category, teamId, matches, sheets, Map.of());
    }

    TeamInput withSummaries(Map<Integer, SeasonMatch> summaries) {
        return withSummaries(summaries, true);
    }

    /** @param complete la ficha lista todos sus partidos (si no, uno sin acta que no figura sigue "sin acta") */
    TeamInput withSummaries(Map<Integer, SeasonMatch> summaries, boolean complete) {
        return new TeamInput(competition, category, teamId, matches, sheets, events, summaries, complete);
    }

    /** ¿El jugador figura en este equipo, en alguna planilla o en su ficha de temporada? */
    boolean playedBy(int playerId) {
        return !summaries.isEmpty()
                || sheets.values().stream().anyMatch(s -> s.stream().anyMatch(p -> p.playerId() == playerId));
    }
}
