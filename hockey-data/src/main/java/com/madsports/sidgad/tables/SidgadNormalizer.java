package com.madsports.sidgad.tables;

import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.SeasonMatch;
import com.madsports.sidgad.model.Models.StandingRow;
import com.madsports.sidgad.parse.SidgadParser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pasa el HTML guardado de SIDGAD a las tablas propias, con {@link SidgadParser}: no pide nada a SIDGAD. Es
 * idempotente: normalizar dos veces lo mismo deja las mismas filas (las competiciones se actualizan por idc; los
 * equipos, los partidos, la tabla y las estadísticas de una competición, la planilla y los eventos de un acta, y los
 * partidos de la ficha de un jugador, se reemplazan).
 */
@Component
public class SidgadNormalizer {

    private final SidgadCompetitionRepository competitions;
    private final SidgadTeamRepository teams;
    private final SidgadMatchRepository matches;
    private final SidgadPlayerRepository players;
    private final SidgadMatchLineupRepository lineups;
    private final SidgadMatchEventRepository events;
    private final SidgadStandingRepository standings;
    private final SidgadPlayerStatsRepository stats;
    private final SidgadPlayerSeasonMatchRepository seasons;
    private final Clock clock;

    public SidgadNormalizer(SidgadCompetitionRepository competitions, SidgadTeamRepository teams,
                            SidgadMatchRepository matches, SidgadPlayerRepository players,
                            SidgadMatchLineupRepository lineups, SidgadMatchEventRepository events,
                            SidgadStandingRepository standings, SidgadPlayerStatsRepository stats,
                            SidgadPlayerSeasonMatchRepository seasons, Clock clock) {
        this.competitions = competitions;
        this.teams = teams;
        this.matches = matches;
        this.players = players;
        this.lineups = lineups;
        this.events = events;
        this.standings = standings;
        this.stats = stats;
        this.seasons = seasons;
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
            Map<Integer, SidgadTeam> unique = new LinkedHashMap<>();
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

    /**
     * El acta de un partido ({@code sidgad_game_sheet}): reemplaza su planilla y sus eventos, y actualiza el nombre de
     * cada jugador. Un acta sin publicar ("File not found.", vacía) deja el partido sin planilla ni eventos.
     *
     * @return los jugadores de la planilla
     */
    @Transactional
    public int sheet(int idp, String sheet) {
        List<LineupPlayer> lineup = SidgadParser.lineups(sheet);
        List<MatchEvent> parsed = SidgadParser.gameEvents(sheet);
        lineups.deleteByIdp(idp);
        events.deleteByIdp(idp);
        lineups.flush();
        lineups.saveAll(lineup.stream().map(p -> new SidgadMatchLineup(idp, p)).toList());
        events.saveAll(parsed.stream().map(e -> new SidgadMatchEvent(idp, e)).toList());
        // Los jugadores, una vez cada uno: los que ya están, con el nombre al día.
        Map<Integer, LineupPlayer> byId = new LinkedHashMap<>();
        lineup.stream().filter(p -> p.playerId() > 0).forEach(p -> byId.putIfAbsent(p.playerId(), p));
        Map<Integer, SidgadPlayer> known = players.findAllById(byId.keySet()).stream()
                .collect(Collectors.toMap(SidgadPlayer::getId, Function.identity()));
        Instant now = clock.instant();
        players.saveAll(byId.values().stream().map(p -> {
            SidgadPlayer row = known.getOrDefault(p.playerId(), new SidgadPlayer(p.playerId()));
            row.update(p.name(), p.surname(), now);
            return row;
        }).toList());
        return lineup.size();
    }

    /**
     * La tabla de posiciones de una competición ({@code sidgad:standings:<idc>}): la reemplaza. Si la competición
     * todavía no está, no hace nada.
     *
     * @return las filas normalizadas
     */
    @Transactional
    public int standings(int idc, String standings) {
        if (!competitions.existsById(idc)) {
            return 0;
        }
        List<StandingRow> parsed = blank(standings) ? List.of() : SidgadParser.standings(standings);
        this.standings.deleteByCompetitionId(idc);
        this.standings.flush();
        this.standings.saveAll(parsed.stream().map(r -> new SidgadStanding(idc, r)).toList());
        return parsed.size();
    }

    /**
     * Las estadísticas por jugador de una competición ({@code sidgad:stats:<idc>}): las reemplaza. Si la competición
     * todavía no está, no hace nada.
     *
     * @return los jugadores normalizados
     */
    @Transactional
    public int playerStats(int idc, String playerStats) {
        if (!competitions.existsById(idc)) {
            return 0;
        }
        List<PlayerStats> parsed = blank(playerStats) ? List.of() : SidgadParser.playerStats(playerStats);
        stats.deleteByCompetitionId(idc);
        stats.flush();
        stats.saveAll(parsed.stream().map(p -> new SidgadPlayerStats(idc, p)).toList());
        return parsed.size();
    }

    /**
     * La ficha de temporada de un jugador ({@code sidgad:profile:<jugador>:<temporada>}): reemplaza sus partidos de
     * esa temporada.
     *
     * @return los partidos normalizados
     */
    @Transactional
    public int playerSeason(int playerId, int seasonId, String profile) {
        List<SeasonMatch> parsed = SidgadParser.seasonMatches(profile);
        seasons.deleteByPlayerIdAndSeasonId(playerId, seasonId);
        seasons.flush();
        seasons.saveAll(parsed.stream().map(m -> new SidgadPlayerSeasonMatch(playerId, seasonId, m)).toList());
        return parsed.size();
    }

    private static boolean blank(String html) {
        return html == null || html.isBlank();
    }
}
