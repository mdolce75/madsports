package com.madsports.source;

import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.MatchEvent;

import java.util.List;

/**
 * El acta de un partido: sus eventos y las planillas de los dos equipos. Las listas vienen vacías si el acta no está
 * publicada. Cada llamada puede volver a interpretar el acta: quien la use varias veces, que guarde el resultado.
 */
public interface GameSheet {

    /** Un acta sin publicar. */
    GameSheet EMPTY = of(List.of(), List.of());

    List<MatchEvent> events();

    List<LineupPlayer> lineups();

    static GameSheet of(List<MatchEvent> events, List<LineupPlayer> lineups) {
        return new GameSheet() {
            public List<MatchEvent> events() {
                return events;
            }

            public List<LineupPlayer> lineups() {
                return lineups;
            }
        };
    }
}
