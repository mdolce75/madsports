package com.madsports.stats;

import com.madsports.sidgad.model.Models.TeamRef;

/** Un equipo del club en una competición, con lo extraído de ella. */
record TeamEntry(StatsEngine.CompetitionData data, TeamRef team) {
}
