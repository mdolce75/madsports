package com.madsports.report;

import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.SeasonMatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Estadísticas por jugador de los equipos del club armadas con la ficha de temporada de cada jugador.
 * <p>
 * La tabla de estadísticas de SIDGAD solo suma los partidos con acta publicada (y en algunas competiciones no se
 * publica): los goles de las goleadoras no llegan a los goles del equipo. La ficha de temporada lista todos los
 * partidos del jugador, también los que no tienen acta, con sus goles, libres, penales, azules y rojas.
 */
final class SeasonScorers {

    private SeasonScorers() {
    }

    /**
     * Las filas de los jugadores del club en una competición, según su ficha de temporada.
     *
     * @param teamOf   jugador → equipo del club en que juega en esta competición (de las actas y de la tabla de SIDGAD)
     * @param names    jugador → nombre
     * @param profiles jugador → partidos de su ficha de temporada (todas las competiciones)
     * @param table    la tabla de SIDGAD de esta competición: queda para quien no tenga ficha o no figure en ella
     */
    static List<PlayerStats> forCompetition(Competition c, int clubId, Map<Integer, Integer> teamOf,
                                            Map<Integer, String> names, Map<Integer, List<SeasonMatch>> profiles,
                                            List<PlayerStats> table) {
        List<PlayerStats> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : teamOf.entrySet()) {
            int playerId = e.getKey();
            List<SeasonMatch> rows = profiles.getOrDefault(playerId, List.of()).stream()
                    .filter(s -> sameCompetition(s.competition(), c.name()))
                    .toList();
            if (rows.isEmpty()) {
                // Sin ficha (o no figura en ella): queda lo que diga la tabla de SIDGAD, si está.
                table.stream().filter(p -> p.playerId() == playerId && p.teamId() == e.getValue()).forEach(out::add);
                continue;
            }
            String code = table.stream().filter(p -> p.teamId() == e.getValue()).map(PlayerStats::teamCode)
                    .findFirst().orElse("");
            out.add(new PlayerStats(playerId, e.getValue(), code, clubId, names.getOrDefault(playerId, ""),
                    sum(rows, SeasonMatch::goals), rows.size(), sum(rows, SeasonMatch::assists),
                    sum(rows, SeasonMatch::penaltiesScored), sum(rows, SeasonMatch::penaltiesTaken),
                    sum(rows, SeasonMatch::directFreeScored), sum(rows, SeasonMatch::directFreeTaken),
                    sum(rows, SeasonMatch::blueCards), sum(rows, SeasonMatch::redCards)));
        }
        return out;
    }

    /**
     * ¿El encabezado de la ficha ("TORNEO APERTURA JUNIORS FEMENINO 2 (FPP)") es esta competición? Igual sin
     * distinguir mayúsculas ni espacios: "JUNIORS FEMENINO" y "JUNIORS FEMENINO 2" son competiciones distintas.
     */
    static boolean sameCompetition(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toUpperCase().replaceAll("\\s+", " ").trim();
    }

    private static int sum(List<SeasonMatch> rows, java.util.function.ToIntFunction<SeasonMatch> f) {
        return rows.stream().mapToInt(f).sum();
    }
}
