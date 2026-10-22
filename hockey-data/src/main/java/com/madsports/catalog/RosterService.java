package com.madsports.catalog;

import com.madsports.catalog.CatalogService.CategoryOption;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.parse.SidgadParser;
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

    /**
     * Un jugador del plantel para la planilla del DT.
     *
     * @param numero  número de camiseta más usado en las actas ("07"; vacío si no figura)
     * @param arquero figura como arquero ("P") en 2 planillas o más, o en la mayoría de las suyas
     */
    public record SquadMember(int id, String number, String name, boolean goalkeeper) {
    }

    /** Un equipo del club en el año: sus competiciones, sus ids de equipo en SIDGAD y su nombre ("Infantil B Femenino"). */
    private record Team(ClubView club, List<Competition> comps, Set<Integer> teamIds, String label) {
    }

    private final CatalogService catalog;
    private final SidgadClient sidgad;
    /** Plantel por equipo (club, año, categoría, rama): las actas ya quedan en cache, esto evita re-parsearlas. */
    private final Cache<String, List<RosterPlayer>> cache =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(200).build();
    private final Cache<String, List<SquadMember>> squadCache =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(200).build();

    public RosterService(CatalogService catalog, SidgadClient sidgad) {
        this.catalog = catalog;
        this.sidgad = sidgad;
    }

    /**
     * @param categoria opción de categoría del club ("INFANTIL" o "INFANTIL:B")
     * @param rama      MASCULINO o FEMENINO
     */
    public List<RosterPlayer> players(Federation fed, int clubId, int year, String category, String branch) {
        String key = cacheKey(fed, clubId, year, category, branch);
        return cache.get(key, k -> {
            Team e = team(fed, clubId, year, category, branch);
            return roster(lineups(e), e.label());
        });
    }

    /**
     * Plantel del equipo para la planilla del DT: cada jugador que figura en sus actas, con su número más usado y si
     * es arquero.
     */
    public List<SquadMember> squad(Federation fed, int clubId, int year, String category, String branch) {
        String key = cacheKey(fed, clubId, year, category, branch);
        return squadCache.get(key, k -> squad(lineups(team(fed, clubId, year, category, branch))));
    }

    private String cacheKey(Federation fed, int clubId, int year, String category, String branch) {
        CategoryOption option = CategoryOption.parse(category);
        return fed + "|" + clubId + "|" + year + "|" + option.key() + "|" + (branch == null ? "" : branch.trim().toUpperCase());
    }

    private Team team(Federation fed, int clubId, int year, String categoryKey, String branch) {
        ClubView club = catalog.club(fed, clubId, year);
        CategoryOption option = CategoryOption.parse(categoryKey);
        Competition.Category category = catalog.category(fed, option.level(), branch);
        String teamKey = option.teamKey(club.code());
        List<Competition> comps = catalog.competitionsFor(fed, club.id(), category, null, year);
        Map<Integer, String> keys = ClubTeams.keys(comps, club);
        Set<Integer> teamIds = keys.entrySet().stream().filter(e -> e.getValue().equals(teamKey))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (comps.isEmpty() || teamIds.isEmpty()) {
            throw new IllegalArgumentException(club.name() + " no tiene ese equipo en " + year + ".");
        }
        return new Team(club, comps, teamIds, ClubTeams.label(comps.getFirst(), club, teamKey));
    }

    private Map<Competition, List<Match>> calendars(Team e) {
        Map<Competition, List<Match>> out = new LinkedHashMap<>();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<Competition, Future<String>> calendars = new LinkedHashMap<>();
            e.comps().forEach(c -> calendars.put(c, io.submit(() -> sidgad.calendar(c.id()))));
            calendars.forEach((c, f) -> out.put(c, SidgadParser.calendar(ClubTeams.await(f))));
        }
        return out;
    }

    /** Planillas del equipo en sus partidos jugados con acta (cada acta trae la del local y la del visitante). */
    private List<LineupPlayer> lineups(Team e) {
        List<LineupPlayer> lineups = new ArrayList<>();
        Map<Competition, List<Match>> calendars = calendars(e);
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<Match, Future<String>> sheets = new LinkedHashMap<>();
            calendars.forEach((c, matches) -> matches.stream()
                    .filter(m -> m.played() && m.idp() > 0
                            && (e.teamIds().contains(m.homeTeamId()) || e.teamIds().contains(m.awayTeamId())))
                    .forEach(m -> sheets.put(m, io.submit(() -> sidgad.gameSheet(c.id(), m.idp())))));
            sheets.forEach((m, sheet) -> lineups.addAll(ownSide(SidgadParser.lineups(ClubTeams.await(sheet)), m, e.teamIds())));
        }
        return lineups;
    }

    /** Un jugador por id_player: su número más usado, y arquero si figura con "P" en 2 planillas o en la mayoría. */
    static List<SquadMember> squad(List<LineupPlayer> lineups) {
        Map<Integer, List<LineupPlayer>> byId = lineups.stream()
                .collect(Collectors.groupingBy(LineupPlayer::playerId, LinkedHashMap::new, Collectors.toList()));
        return byId.values().stream()
                .map(ps -> {
                    long gk = ps.stream().filter(LineupPlayer::goalkeeper).count();
                    String number = ps.stream().map(LineupPlayer::number).filter(n -> n != null && !n.isBlank())
                            .collect(Collectors.groupingBy(n -> n, LinkedHashMap::new, Collectors.counting()))
                            .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
                    return new SquadMember(ps.getFirst().playerId(), number, ps.getFirst().name(),
                            gk >= 2 || (gk > 0 && gk * 2 >= ps.size()));
                })
                .sorted(Comparator.comparing(SquadMember::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Jugadores de la planilla del equipo del club en ese partido (local o visitante). */
    public static List<LineupPlayer> ownSide(List<LineupPlayer> lineups, Match m, Set<Integer> teamIds) {
        boolean home = teamIds.contains(m.homeTeamId());
        return lineups.stream().filter(p -> p.home() == home).toList();
    }

    public static List<RosterPlayer> roster(List<LineupPlayer> lineups, String category) {
        Map<Integer, LineupPlayer> players = new LinkedHashMap<>();
        lineups.forEach(p -> players.putIfAbsent(p.playerId(), p));
        return players.values().stream()
                .map(p -> RosterPlayer.of(p.playerId(), p.name(), p.surname(), category))
                .sorted(Comparator.comparing(RosterPlayer::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** ¿El jugador figura en ese equipo del club? (valida lo que se elige en el registro). */
    public Optional<RosterPlayer> find(Federation fed, int clubId, int year, String category, String branch,
                                       int playerId) {
        return players(fed, clubId, year, category, branch).stream().filter(p -> p.id() == playerId).findFirst();
    }
}
