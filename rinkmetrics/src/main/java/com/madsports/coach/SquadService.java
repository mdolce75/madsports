package com.madsports.coach;

import com.madsports.account.UserAccount;
import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.catalog.CatalogService.LevelView;
import com.madsports.coach.TeamService.TeamView;
import com.madsports.catalog.RosterService;
import com.madsports.catalog.RosterService.SquadMember;
import com.madsports.catalog.RosterService.RosterPlayer;
import com.madsports.sidgad.model.Competition;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Collator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Jugadores del DT (el ABM de jugadores de la sección Equipo): nombre, apellido, número, si es arquero y en qué equipos
 * juega (uno o más). El plantel de un equipo son sus jugadores activos; no sale de SIDGAD. Las acciones nombran al
 * jugador por su id. Un equipo se puede armar desde una categoría del club en SIDGAD: su plantel (los jugadores de las
 * actas del equipo) entra entero, salvo los que el DT deja afuera, que quedan como "no incluidos" para sumarlos después.
 */
@Service
public class SquadService {

    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}");
    /** Intentos de un alta si otra simultánea del mismo DT toma el mismo id (un doble toque, dos dispositivos). */
    static final int SIGNUP_ATTEMPTS = 10;

    /**
     * Orden alfabético en castellano, sin distinguir mayúsculas ni acentos (Álvarez antes que Gómez): el mismo que usan
     * la pantalla y la demo ({@code localeCompare(..., "es", {sensitivity: "base"})}).
     */
    static final Comparator<String> ALPHABETICAL = alphabetical();

    private static Comparator<String> alphabetical() {
        Collator c = Collator.getInstance(Locale.forLanguageTag("es"));
        c.setStrength(Collator.PRIMARY);
        return c::compare;
    }

    /**
     * Un jugador.
     *
     * @param id      el id con el que lo nombran las acciones
     * @param nombre  nombre y apellido, para mostrar
     * @param nombres solo el nombre (para editarlo)
     * @param equipos    ids de los equipos en que juega
     * @param pendientes ids de los equipos en cuyo plantel de SIDGAD figura pero no fue incluido
     */
    public record SquadPlayer(int id, String number, String name, String names, String lastName,
                                 boolean goalkeeper, List<Long> teams, List<Long> pending) {

        static SquadPlayer of(TeamPlayer j) {
            return new SquadPlayer(j.getPlayerId(), j.getNumber() == null ? "" : j.getNumber(), j.fullName(),
                    j.getName(), j.getLastName(), j.isGoalkeeper(), j.getTeams().stream().sorted().toList(),
                    j.getPending().stream().sorted().toList());
        }
    }

    /**
     * Una categoría del club del DT en SIDGAD (un equipo del club), para armar un equipo con su plantel.
     *
     * @param clave  nivel y, si el club tiene más de un equipo, la letra: "PRIMERA" o "INFANTIL:B"
     * @param rama   MASCULINO o FEMENINO
     * @param nombre "Primera Masculino", "Infantil B Femenino"
     */
    public record ClubCategoryView(String password, String branch, String name) {
    }

    /**
     * Un jugador del plantel de SIDGAD de una categoría del club: los que figuran en las actas del equipo.
     *
     * @param numero  su número más usado en las actas (vacío si no figura)
     * @param arquero figura como arquero en sus planillas
     */
    public record SidgadPlayer(int id, String number, String name, String names, String lastName, boolean goalkeeper) {
    }

    /** Equipo nuevo: nombre y, si sale de SIDGAD, la categoría del club y los jugadores de su plantel que no van. */
    public record NewTeam(String name, String category, String branch, List<Integer> excluded) {
    }

    /** Alta o cambio de un jugador: sus datos y los equipos en que juega (al menos uno). */
    public record PlayerData(String name, String lastName, String number, Boolean goalkeeper, List<Long> teams) {
    }

    private final TeamPlayerRepository players;
    private final TeamService teams;
    private final CatalogService catalog;
    private final RosterService roster;
    private final Clock clock;
    private final TransactionTemplate tx;

    public SquadService(TeamPlayerRepository players, TeamService teams, CatalogService catalog,
                            RosterService roster, Clock clock, PlatformTransactionManager transactions) {
        this.players = players;
        this.teams = teams;
        this.catalog = catalog;
        this.roster = roster;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    // ---------- Equipos desde SIDGAD ----------

    /** Las categorías del club del DT en SIDGAD (cada equipo del club en cada rama), primero las masculinas. */
    public List<ClubCategoryView> clubCategories(UserAccount u) {
        ClubView club = catalog.club(u.federation(), u.getClubId(), catalog.defaultYear());
        List<ClubCategoryView> out = new ArrayList<>();
        for (String branch : List.of("MASCULINO", "FEMENINO")) {
            for (LevelView lv : club.categories()) {
                if (lv.branches().contains(branch)) {
                    out.add(new ClubCategoryView(lv.key(), branch,
                            lv.label() + " " + new Competition.Category("", branch).branchLabel()));
                }
            }
        }
        return out;
    }

    /** El plantel de SIDGAD de la categoría del club: los jugadores de las actas del equipo, por apellido. */
    public List<SidgadPlayer> sidgadSquad(UserAccount u, String category, String branch) {
        ClubCategoryView c = clubCategory(u, category, branch);
        int year = catalog.defaultYear();
        Map<Integer, SquadMember> data = roster.squad(u.federation(), u.getClubId(), year, c.password(), c.branch())
                .stream().collect(Collectors.toMap(SquadMember::id, Function.identity(), (a, b) -> a));
        return roster.players(u.federation(), u.getClubId(), year, c.password(), c.branch()).stream()
                .map(p -> {
                    SquadMember d = data.get(p.id());
                    return new SidgadPlayer(p.id(), d == null ? "" : sidgadNumber(d.number()), p.name(),
                            shortName(p.firstName()), shortName(p.lastName()), d != null && d.goalkeeper());
                })
                .sorted(Comparator.comparing(SidgadPlayer::lastName, ALPHABETICAL)
                        .thenComparing(SidgadPlayer::names, ALPHABETICAL))
                .toList();
    }

    private ClubCategoryView clubCategory(UserAccount u, String category, String branch) {
        String password = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        String r = branch == null ? "" : branch.trim().toUpperCase(Locale.ROOT);
        return clubCategories(u).stream().filter(c -> c.password().equals(password) && c.branch().equals(r)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Esa categoría no es de tu club."));
    }

    /**
     * Crea un equipo. Con una categoría del club, se arma con su plantel de SIDGAD: entran todos sus jugadores salvo
     * los {@code excluded}, que quedan como "no incluidos" en el equipo (figuran en la lista y se suman después). Un
     * jugador que el DT ya tiene (por otro equipo) no se duplica: suma este equipo. Sin nombre, el de la categoría.
     */
    @Transactional
    public TeamView createTeam(UserAccount u, NewTeam n) {
        if (n.category() == null || n.category().isBlank()) {
            return teams.create(u, n.name());
        }
        ClubCategoryView c = clubCategory(u, n.category(), n.branch());
        List<SidgadPlayer> squad = sidgadSquad(u, c.password(), c.branch());
        String name = n.name() == null || n.name().isBlank() ? c.name() : n.name();
        Team e = teams.created(u, name, c.password(), c.branch());
        Set<Integer> out = new HashSet<>(n.excluded() == null ? List.of() : n.excluded());
        Set<String> usedNumbers = new HashSet<>();
        // Primero los incluidos: si dos tienen el mismo número, se lo queda uno que juega.
        List<SidgadPlayer> order = squad.stream()
                .sorted(Comparator.comparing((SidgadPlayer p) -> out.contains(p.id()))).toList();
        for (SidgadPlayer p : order) {
            boolean included = !out.contains(p.id());
            TeamPlayer j = players.findByUserIdAndPlayerId(u.getId(), p.id()).orElse(null);
            if (j == null) {
                // El número, si no lo usa ya otro del plantel (dos con el mismo número más usado): el segundo, sin número.
                String number = p.number().isEmpty() || !usedNumbers.add(p.number()) ? null : p.number();
                j = new TeamPlayer(u.getId(), p.id(), p.names(), p.lastName(), number, p.goalkeeper(),
                        included ? List.of(e.getId()) : List.of(), clock.instant());
            } else {
                if (!j.isActive()) {
                    j.reactivate();
                }
                if (j.getNumber() != null) {
                    usedNumbers.add(j.getNumber());
                }
            }
            if (included) {
                j.includeIn(e.getId());
            } else {
                j.leavePendingIn(e.getId());
            }
            players.save(j);
        }
        return teams.list(u).stream().filter(v -> v.id().equals(e.getId())).findFirst().orElseThrow();
    }

    /** Suma al equipo a un jugador "no incluido" (o a cualquiera de sus jugadores), si su número está libre ahí. */
    @Transactional
    public SquadPlayer include(UserAccount u, int playerId, Long teamId) {
        TeamPlayer j = own(u, playerId);
        Team e = teams.team(u, teamId);
        checkNumber(u, Set.of(e.getId()), j.getNumber(), playerId);
        j.includeIn(e.getId());
        return SquadPlayer.of(j);
    }

    /** Número de las actas ("07") como lo guarda el DT ("7"); vacío si no es un número de camiseta. */
    private static String sidgadNumber(String n) {
        String t = n == null ? "" : n.trim();
        return NUMBER.matcher(t).matches() ? String.valueOf(Integer.parseInt(t)) : "";
    }

    /** Nombre o apellido de SIDGAD, hasta 40 letras (lo que guarda el plantel). */
    private static String shortName(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return t.length() > 40 ? t.substring(0, 40).trim() : t;
    }

    /** Todos los jugadores activos del DT, por apellido. */
    public List<SquadPlayer> players(UserAccount u) {
        return players.findByUserIdAndActiveTrue(u.getId()).stream()
                .sorted(Comparator.comparing(TeamPlayer::getLastName, ALPHABETICAL)
                        .thenComparing(TeamPlayer::getName, ALPHABETICAL))
                .map(SquadPlayer::of).toList();
    }

    /** El plantel de un equipo (sin id, el primero): sus jugadores activos, por número. */
    public List<SquadPlayer> squad(UserAccount u, Long teamId) {
        Team e = teams.team(u, teamId);
        return ofTeam(u, e.getId()).stream().map(SquadPlayer::of).toList();
    }

    /**
     * Alta de un jugador. El id es el siguiente al último del DT: si dos altas simultáneas toman el mismo, la base
     * rechaza la segunda (id único por DT) y se reintenta en una transacción nueva, que ya ve a la primera (y, si tienen
     * el mismo número, avisa que el número ya es de ese jugador).
     */
    public SquadPlayer addPlayer(UserAccount u, PlayerData d) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(s -> createOnce(u, d));
            } catch (DataIntegrityViolationException e) {
                if (attempt == SIGNUP_ATTEMPTS) {
                    throw new CoachService.CoachConflictException("No se pudo dar de alta al jugador: probá de nuevo.");
                }
                await(attempt);
            }
        }
    }

    /** Una espera corta y al azar antes de reintentar, para que las altas que chocaron no vuelvan a chocar. */
    private static void await(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoachService.CoachConflictException("No se pudo dar de alta al jugador: probá de nuevo.");
        }
    }

    private SquadPlayer createOnce(UserAccount u, PlayerData d) {
        Set<Long> own = validTeams(u, d.teams());
        String number = number(d.number());
        checkNumber(u, own, number, -1);
        int id = players.findTopByUserIdOrderByPlayerIdDesc(u.getId()).map(TeamPlayer::getPlayerId)
                .filter(n -> n >= TeamPlayer.FIRST_OWN_ID).map(n -> n + 1).orElse(TeamPlayer.FIRST_OWN_ID);
        TeamPlayer j = players.saveAndFlush(new TeamPlayer(u.getId(), id, text(d.name(), "nombre"),
                text(d.lastName(), "apellido"), number, Boolean.TRUE.equals(d.goalkeeper()), own, clock.instant()));
        return SquadPlayer.of(j);
    }

    @Transactional
    public SquadPlayer modify(UserAccount u, int playerId, PlayerData d) {
        TeamPlayer j = own(u, playerId);
        Set<Long> own = validTeams(u, d.teams());
        String number = number(d.number());
        checkNumber(u, own, number, playerId);
        j.update(text(d.name(), "nombre"), text(d.lastName(), "apellido"), number, Boolean.TRUE.equals(d.goalkeeper()),
                own);
        return SquadPlayer.of(j);
    }

    /** Baja: el jugador sale de sus equipos; sus acciones quedan en los partidos e informes. */
    @Transactional
    public void deactivate(UserAccount u, int playerId) {
        own(u, playerId).deactivate();
    }

    private List<TeamPlayer> ofTeam(UserAccount u, Long teamId) {
        return players.findByUserIdAndActiveTrue(u.getId()).stream().filter(j -> j.playsIn(teamId))
                .sorted(Comparator.comparingInt((TeamPlayer j) -> order(j.getNumber()))
                        .thenComparing(TeamPlayer::getLastName, ALPHABETICAL))
                .toList();
    }

    private TeamPlayer own(UserAccount u, int playerId) {
        return players.findByUserIdAndPlayerIdAndActiveTrue(u.getId(), playerId)
                .orElseThrow(() -> new CoachService.NotFoundException("Ese jugador no está en tus equipos."));
    }

    /** Al menos un equipo, y todos del DT. */
    private Set<Long> validTeams(UserAccount u, List<Long> requests) {
        Set<Long> ids = new LinkedHashSet<>(requests == null ? List.of() : requests);
        ids.remove(null);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Asigná el jugador a un equipo.");
        }
        ids.forEach(id -> teams.team(u, id));
        return ids;
    }

    /** El número no se repite dentro de un mismo equipo. */
    private void checkNumber(UserAccount u, Set<Long> playerTeams, String number, int except) {
        if (number == null) {
            return;
        }
        players.findByUserIdAndActiveTrue(u.getId()).stream()
                .filter(j -> j.getPlayerId() != except && number.equals(j.getNumber()))
                .filter(j -> j.getTeams().stream().anyMatch(playerTeams::contains))
                .findFirst()
                .ifPresent(j -> {
                    throw new CoachService.CoachConflictException("El número " + number + " ya es de " + j.fullName() + " en ese equipo.");
                });
    }

    private static String text(String s, String field) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Escribí el " + field + " del jugador.");
        }
        if (t.length() > 40) {
            throw new IllegalArgumentException("El " + field + " es muy largo (hasta 40 letras).");
        }
        return t;
    }

    /** Número de camiseta: de 1 a 3 cifras, sin ceros adelante; vacío = sin número. */
    private static String number(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (!NUMBER.matcher(t).matches()) {
            throw new IllegalArgumentException("El número es de 1 a 3 cifras.");
        }
        return String.valueOf(Integer.parseInt(t));
    }

    private static int order(String number) {
        return number == null ? 9999 : Integer.parseInt(number);
    }
}
