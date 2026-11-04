package com.madsports.sidgad.tables;

import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.parse.SidgadParser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pasa el HTML guardado de SIDGAD a las tablas propias, con {@link SidgadParser}: no pide nada a SIDGAD. Es
 * idempotente: normalizar dos veces lo mismo deja las mismas filas (las competiciones se actualizan por idc; los
 * equipos y los partidos de una competición se reemplazan).
 */
@Component
public class SidgadNormalizer {

    private final SidgadCompetitionRepository competitions;
    private final SidgadTeamRepository teams;
    private final SidgadMatchRepository matches;
    private final Clock clock;

    public SidgadNormalizer(SidgadCompetitionRepository competitions, SidgadTeamRepository teams,
                            SidgadMatchRepository matches, Clock clock) {
        this.competitions = competitions;
        this.teams = teams;
        this.matches = matches;
        this.clock = clock;
    }

    /**
     * El catálogo ({@code sidgad:ls}): cada competición con sus equipos.
     *
     * @param portal el portal ({@code sidgad:portal}), para el año de cada temporada; null si no se tiene
     * @return las competiciones normalizadas
     */
    @Transactional
    public int catalog(String catalog, String portal) {
        Map<Integer, Integer> yearOfSeason = new HashMap<>();
        if (portal != null && !portal.isBlank()) {
            SidgadParser.seasons(portal).forEach((year, season) -> yearOfSeason.put(season, year));
        }
        Instant now = clock.instant();
        List<Competition> parsed = SidgadParser.catalog(catalog);
        for (Competition c : parsed) {
            SidgadCompetition row = competitions.findById(c.id()).orElseGet(() -> new SidgadCompetition(c.id()));
            row.update(c, yearOfSeason.get(c.seasonId()), now);
            competitions.save(row);
            teams.deleteByCompetitionId(c.id());
            // Los borrados antes que las altas (Hibernate inserta primero): si no, choca el (competición, equipo) único.
            teams.flush();
            // Un equipo por id (por si el catálogo lo repite).
            Map<Integer, SidgadTeam> unique = new java.util.LinkedHashMap<>();
            c.teams().forEach(t -> unique.putIfAbsent(t.teamId(), new SidgadTeam(c.id(), t)));
            teams.saveAll(unique.values());
        }
        return parsed.size();
    }

    /**
     * El calendario de una competición ({@code sidgad:calendar:<idc>}): reemplaza sus partidos. Si la competición
     * todavía no está (no se normalizó el catálogo), no hace nada.
     *
     * @return los partidos normalizados
     */
    @Transactional
    public int calendar(int idc, String calendar) {
        if (!competitions.existsById(idc)) {
            return 0;
        }
        List<Match> parsed = calendar == null || calendar.isBlank() ? List.of() : SidgadParser.calendar(calendar);
        matches.deleteByCompetitionId(idc);
        matches.flush();
        matches.saveAll(parsed.stream().map(m -> new SidgadMatch(idc, m)).toList());
        return parsed.size();
    }
}
