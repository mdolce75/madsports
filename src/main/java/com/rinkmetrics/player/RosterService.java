package com.rinkmetrics.player;

import com.rinkmetrics.catalog.CatalogService;
import com.rinkmetrics.catalog.CatalogService.CategoryOption;
import com.rinkmetrics.catalog.CatalogService.ClubView;
import com.rinkmetrics.sidgad.SidgadClient;
import com.rinkmetrics.sidgad.model.Competition;
import com.rinkmetrics.sidgad.model.Models.LineupPlayer;
import com.rinkmetrics.sidgad.model.Models.Match;
import com.rinkmetrics.sidgad.parse.SidgadParser;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Plantel de un equipo del club en el año, para que el jugador se elija en el registro. Sale de las actas de los
 * partidos jugados por el equipo (planilla de jugadores citados): la tabla de estadísticas de la FPP solo lista a los
 * que hicieron goles. El {@code id_player} de SIDGAD es el mismo en todas las competiciones, así que identifica al
 * jugador sin depender de cómo esté escrito el nombre.
 */
@Service
public class RosterService {

    /**
     * @param name      nombre completo ("Juan Ignacio Perez Barbosa")
     * @param firstName nombre(s) ("Juan Ignacio") y {@code lastName} apellido(s) ("Perez Barbosa"), como los separa el acta
     * @param category  equipo en que figura ("Infantil B Femenino")
     */
    public record RosterPlayer(int id, String name, String firstName, String lastName, String category) {

        /** Separa nombre y apellido sabiendo el apellido; sin apellido, la última palabra. */
        static RosterPlayer of(int id, String name, String surname, String category) {
            String last = surname == null || surname.isBlank() || !name.endsWith(surname)
                    ? name.substring(name.lastIndexOf(' ') + 1) : surname;
            String first = name.substring(0, name.length() - last.length()).trim();
            return new RosterPlayer(id, name, first, last, category);
        }
    }

    private final CatalogService catalog;
    private final SidgadClient sidgad;
    /** Plantel por equipo (club, año, categoría, rama): las actas ya quedan en cache, esto evita re-parsearlas. */
    private final Cache<String, List<RosterPlayer>> cache =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(200).build();

    public RosterService(CatalogService catalog, SidgadClient sidgad) {
        this.catalog = catalog;
        this.sidgad = sidgad;
    }

    /**
     * @param categoria opción de categoría del club ("INFANTIL" o "INFANTIL:B")
     * @param rama      MASCULINO o FEMENINO
     */
    public List<RosterPlayer> players(int clubId, int year, String categoria, String rama) {
        ClubView club = catalog.club(clubId, year);
        CategoryOption option = CategoryOption.parse(categoria);
        Competition.Category category = catalog.category(option.level(), rama);
        return cache.get(clubId + "|" + year + "|" + option.key() + "|" + category.branch(),
                k -> load(club, year, category, option.teamKey(club.code())));
    }

    private List<RosterPlayer> load(ClubView club, int year, Competition.Category category, String teamKey) {
        List<Competition> comps = catalog.competitionsFor(club.id(), category, null, year);
        Map<Integer, String> keys = ClubTeams.keys(comps, club);
        Set<Integer> teamIds = keys.entrySet().stream().filter(e -> e.getValue().equals(teamKey))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (comps.isEmpty() || teamIds.isEmpty()) {
            throw new IllegalArgumentException(club.name() + " no tiene ese equipo en " + year + ".");
        }
        String label = ClubTeams.label(comps.getFirst(), club, teamKey);
        List<LineupPlayer> lineups = new ArrayList<>();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<Competition, Future<String>> calendars = new LinkedHashMap<>();
            comps.forEach(c -> calendars.put(c, io.submit(() -> sidgad.calendar(c.id()))));
            Map<Match, Future<String>> sheets = new LinkedHashMap<>();
            for (Competition c : comps) {
                for (Match m : SidgadParser.calendar(ClubTeams.await(calendars.get(c)))) {
                    if (m.played() && m.idp() > 0 && (teamIds.contains(m.homeTeamId()) || teamIds.contains(m.awayTeamId()))) {
                        sheets.put(m, io.submit(() -> sidgad.gameSheet(c.id(), m.idp())));
                    }
                }
            }
            // Cada acta trae la planilla del local y la del visitante: se toma la del equipo del club.
            sheets.forEach((m, sheet) -> lineups.addAll(ownSide(SidgadParser.lineups(ClubTeams.await(sheet)), m, teamIds)));
        }
        return roster(lineups, label);
    }

    /** Jugadores de la planilla del equipo del club en ese partido (local o visitante). */
    static List<LineupPlayer> ownSide(List<LineupPlayer> lineups, Match m, Set<Integer> teamIds) {
        boolean home = teamIds.contains(m.homeTeamId());
        return lineups.stream().filter(p -> p.home() == home).toList();
    }

    static List<RosterPlayer> roster(List<LineupPlayer> lineups, String category) {
        Map<Integer, LineupPlayer> players = new LinkedHashMap<>();
        lineups.forEach(p -> players.putIfAbsent(p.playerId(), p));
        return players.values().stream()
                .map(p -> RosterPlayer.of(p.playerId(), p.name(), p.surname(), category))
                .sorted(Comparator.comparing(RosterPlayer::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** ¿El jugador figura en ese equipo del club? (valida lo que se elige en el registro). */
    public Optional<RosterPlayer> find(int clubId, int year, String categoria, String rama, int playerId) {
        return players(clubId, year, categoria, rama).stream().filter(p -> p.id() == playerId).findFirst();
    }
}
