package com.fppstats.sidgad.model;

/**
 * Datos crudos tal como los publica SIDGAD, ya normalizados a tipos Java.
 * Agrupados en un solo archivo porque son records chicos sin lógica.
 */
public final class Models {

    private Models() {
    }

    /** Equipo dentro de una competición (el id cambia entre Apertura y Clausura). */
    public record TeamRef(int teamId, int clubId, String code, String name) {
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
     * @param teamId  equipo al que se atribuye (0 si el acta no lo indica; ver {@code clubId}/{@code teamCode})
     */
    public record MatchEvent(int period, int minute, int second, EventType type,
                             int teamId, int clubId, String teamCode, String player) {
    }

    public record PlayerStats(int playerId, int teamId, String teamCode, int clubId, String name,
                              int goals, int games, int assists,
                              int penaltiesScored, int penaltiesTaken,
                              int directFreeScored, int directFreeTaken,
                              int blueCards, int redCards) {
    }
}
