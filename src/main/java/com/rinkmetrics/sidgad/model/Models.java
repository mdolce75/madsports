package com.rinkmetrics.sidgad.model;

/**
 * Datos crudos tal como los publica SIDGAD, ya normalizados a tipos Java.
 * Agrupados en un solo archivo porque son records chicos sin lógica.
 */
public final class Models {

    private Models() {
    }

    /**
     * Equipo dentro de una competición (el id cambia entre Apertura y Clausura).
     *
     * @param logo archivo del escudo en {@code logos_clubes/} (p. ej. "22_10.png"; vacío si no tiene)
     */
    public record TeamRef(int teamId, int clubId, String code, String name, String logo) {

        public TeamRef(int teamId, int clubId, String code, String name) {
            this(teamId, clubId, code, name, "");
        }
    }

    public record StandingRow(String group, int position, int clubId, String name, String code,
                              int points, int played, int won, int drawn, int lost,
                              int goalsFor, int goalsAgainst, int goalDiff) {
    }

    public record Match(int idp, int round, java.time.LocalDate date, java.time.LocalTime time, String venue,
                        int homeTeamId, int homeClubId, String homeName,
                        int awayTeamId, int awayClubId, String awayName,
                        Integer homeGoals, Integer awayGoals) {

        public boolean played() {
            return homeGoals != null && awayGoals != null;
        }

        public boolean involves(int teamId) {
            return homeTeamId == teamId || awayTeamId == teamId;
        }
    }

    /** Tipos de evento del acta que se analizan. Amarilla, azul y roja son tarjetas distintas. */
    public enum EventType { GOAL, YELLOW_CARD, BLUE_CARD, RED_CARD, FOUL }

    /**
     * Evento del acta de un partido.
     *
     * @param period  1 o 2 (0 si el acta trae otro período)
     * @param minute  minuto transcurrido dentro del período
     * @param teamId   equipo al que se atribuye (0 si el acta no lo indica; ver {@code clubId}/{@code teamCode})
     * @param playerId id_player del jugador (0 si el evento no lo trae)
     */
    public record MatchEvent(int period, int minute, int second, EventType type,
                             int teamId, int clubId, String teamCode, String player, int playerId) {

        public MatchEvent(int period, int minute, int second, EventType type,
                          int teamId, int clubId, String teamCode, String player) {
            this(period, minute, second, type, teamId, clubId, teamCode, player, 0);
        }
    }

    /**
     * Fila de un jugador en la planilla de un partido (tabla de jugadores de cada equipo en el acta).
     *
     * @param team    nombre del equipo como figura en el acta ("VELEZ", "GEBA B")
     * @param home    planilla del local (en el acta va primero la del local y después la del visitante)
     * @param starter figura en el cinco inicial
     * @param penaltiesScored  penales convertidos (columna PE del acta: "convertidos/tirados")
     * @param directFreeScored libres directos convertidos (columna FD)
     * @param surname          apellido(s), como figura separado en el acta ("Perez Barbosa"); {@code name} es completo
     */
    public record LineupPlayer(int playerId, String team, boolean home, String number, String name, boolean starter,
                               int goals, int yellowCards, int blueCards, int redCards,
                               int penaltiesScored, int penaltiesTaken, int directFreeScored, int directFreeTaken,
                               String surname) {

        public LineupPlayer(int playerId, String team, boolean home, String number, String name, boolean starter,
                            int goals, int yellowCards, int blueCards, int redCards,
                            int penaltiesScored, int penaltiesTaken, int directFreeScored, int directFreeTaken) {
            this(playerId, team, home, number, name, starter, goals, yellowCards, blueCards, redCards,
                    penaltiesScored, penaltiesTaken, directFreeScored, directFreeTaken, "");
        }

        public LineupPlayer(int playerId, String team, boolean home, String number, String name, boolean starter,
                            int goals, int yellowCards, int blueCards, int redCards) {
            this(playerId, team, home, number, name, starter, goals, yellowCards, blueCards, redCards, 0, 0, 0, 0);
        }
    }

    /**
     * Un partido de la ficha de temporada de un jugador ({@code cap_profileseason}): SIDGAD lo lista aunque el partido
     * no tenga acta publicada, con lo que se le anotó. No trae amarillas, titularidad ni minutos.
     *
     * @param competition nombre de la competición ("TORNEO APERTURA JUVENIL MASCULINO (FPP)")
     * @param idp         id del partido (0 si la fila no lo trae)
     */
    public record SeasonMatch(String competition, java.time.LocalDate date, String home, String away,
                              int homeGoals, int awayGoals, int idp, int goals, int assists,
                              int directFreeScored, int directFreeTaken, int penaltiesScored, int penaltiesTaken,
                              int blueCards, int redCards) {
    }

    public record PlayerStats(int playerId, int teamId, String teamCode, int clubId, String name,
                              int goals, int games, int assists,
                              int penaltiesScored, int penaltiesTaken,
                              int directFreeScored, int directFreeTaken,
                              int blueCards, int redCards) {
    }
}
