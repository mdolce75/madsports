package com.madsports.source;

import com.madsports.sidgad.model.Models.StandingRow;

import java.time.LocalDate;
import java.util.List;

/** Tabla de posiciones de una competición y la fecha en que la fuente la generó (null si no la informa). */
public record Standings(List<StandingRow> rows, LocalDate date) {
}
