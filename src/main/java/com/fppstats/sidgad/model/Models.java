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

    public record PlayerStats(int playerId, int teamId, String teamCode, int clubId, String name,
                              int goals, int games, int assists,
                              int penaltiesScored, int penaltiesTaken,
                              int directFreeScored, int directFreeTaken,
                              int blueCards, int redCards) {
    }
}
